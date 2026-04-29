package com.opencode.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.libtermux.libtermux.LibTermux;
import com.libtermux.libtermux.TermuxConstants;
import com.libtermux.libtermux.TermuxBridge;
import com.libtermux.libtermux.LogLevel;
import com.libtermux.libtermux.InstallState;
import com.libtermux.view.TerminalView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "OpenCode";
    private WebView webView;
    private TerminalView terminalView;
    private LibTermux termux;
    private TermuxBridge bridge;
    private static final int FLASK_PORT = 5000;
    private static final String FLASK_URL = "http://localhost:" + FLASK_PORT;
    private static final int SERVER_START_DELAY_MS = 3500;
    private static final int REQUEST_FOLDER_PICKER = 100;

    private boolean returningFromSettings = false;
    private SharedPreferences prefs;
    private String selectedFolderPath;
    private String storageFolderPath;
    private boolean isInitialized = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("opencode", MODE_PRIVATE);
        selectedFolderPath = prefs.getString("working_dir", "");
        storageFolderPath = prefs.getString("storage_dir", "");

        if (storageFolderPath == null || storageFolderPath.isEmpty()) {
            File extStorage = Environment.getExternalStorageDirectory();
            storageFolderPath = new File(extStorage, "opencode").getAbsolutePath();
        }

        File storageDir = new File(storageFolderPath);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }

        try {
            File storageFile = new File(getApplicationContext().getFilesDir(), "storage_dir.txt");
            java.io.FileWriter writer = new java.io.FileWriter(storageFile);
            writer.write(storageFolderPath);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        setupFullscreen();
        requestFileAccess();

        terminalView = findViewById(R.id.terminalview);
        webView = findViewById(R.id.webview);

        initLibTermux();
    }

    private void initLibTermux() {
        termux = LibTermux.builder(this)
            .autoInstall(true)
            .logLevel(LogLevel.DEBUG)
            .build();

        new Thread(() -> {
            try {
                termux.initializeBlocking();
                bridge = termux.getBridge();
                isInitialized = true;

                runOnUiThread(() -> {
                    setupTerminalWithLibTermux();
                    setupWebView();
                    webView.loadData(LOADING_HTML, "text/html", "UTF-8");
                    startFlaskServer();
                });
            } catch (Exception e) {
                Log.e(TAG, "LibTermux init failed", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Init error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    setupWebView();
                });
            }
        }).start();
    }

    private void setupTerminalWithLibTermux() {
        if (bridge == null) return;

        terminalView.setTextSize(14);
        
        try {
            TerminalSession session = termux.getSessions().createSession("main");
            terminalView.attachSession(session);
            session.run("bash");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create terminal session", e);
        }
    }

    private void startFlaskServer() {
        new Thread(() -> {
            try {
                bridge.run("cd " + storageFolderPath + " && mkdir -p opencode 2>/dev/null");
                
                String pipCmd = "pip3 install flask requests -q 2>/dev/null || pip install flask requests -q 2>/dev/null";
                bridge.run(pipCmd);
            } catch (Exception e) {
                Log.e(TAG, "Failed to setup Python", e);
            }
        }).start();

        new Handler(Looper.getMainLooper()).postDelayed(
            () -> webView.loadUrl(FLASK_URL), SERVER_START_DELAY_MS);
    }

    private void setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setupFullscreen();
    }

    private void requestFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                returningFromSettings = true;
                try {
                    startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 1001);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (returningFromSettings) {
            returningFromSettings = false;
            new Handler(Looper.getMainLooper()).postDelayed(
                () -> webView.loadUrl(FLASK_URL), 500);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("http://localhost");
            }
        });
    }

    class AndroidBridge {
        @JavascriptInterface
        public String getWorkingDir() {
            return selectedFolderPath != null ? selectedFolderPath : "";
        }

        @JavascriptInterface
        public void setWorkingDir(String path) {
            selectedFolderPath = path;
            prefs.edit().putString("working_dir", path).apply();
        }

        @JavascriptInterface
        public void openFolderPicker() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_FOLDER_PICKER);
        }

        @JavascriptInterface
        public String listFiles(String path) {
            if (path == null || path.isEmpty()) {
                path = Environment.getExternalStorageDirectory().getAbsolutePath();
            }
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                return "[]";
            }
            List<String> files = new ArrayList<>();
            File[] items = dir.listFiles();
            if (items != null) {
                for (File f : items) {
                    files.add(f.getName() + (f.isDirectory() ? "/" : ""));
                }
            }
            return files.toString();
        }

        @JavascriptInterface
        public void runInTerminal(String command) {
            if (isInitialized && bridge != null) {
                try {
                    bridge.run(command);
                } catch (Exception e) {
                    Log.e(TAG, "Terminal command failed", e);
                }
            }
        }

        @JavascriptInterface
        public void toggleTerminal() {
            runOnUiThread(() -> {
                if (webView.getVisibility() == View.VISIBLE) {
                    webView.setVisibility(View.GONE);
                    terminalView.setVisibility(View.VISIBLE);
                } else {
                    webView.setVisibility(View.VISIBLE);
                    terminalView.setVisibility(View.GONE);
                }
            });
        }

        @JavascriptInterface
        public String runCommand(String command) {
            if (isInitialized && bridge != null) {
                try {
                    return bridge.run(command).getStdout();
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
            return "LibTermux not initialized";
        }

        @JavascriptInterface
        public String getPythonVersion() {
            if (isInitialized && bridge != null) {
                try {
                    return bridge.run("python3 --version").getStdout();
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
            return "Not initialized";
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FOLDER_PICKER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;
            
            getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            String path = treeUri.getPath();
            String authority = treeUri.getAuthority();

            if (path != null) {
                if ("com.android.providers.downloads.documents".equals(authority)) {
                    selectedFolderPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
                } 
                else if (path.contains(":")) {
                    String[] split = path.split(":", 2); 
                    String type = split[0];
                    String relativePath = split.length > 1 ? split[1] : "";

                    if (type.endsWith("primary")) {
                        selectedFolderPath = Environment.getExternalStorageDirectory().getAbsolutePath();
                        if (!relativePath.isEmpty()) {
                            selectedFolderPath += "/" + relativePath;
                        }
                    } else {
                        String volumeId = type.substring(type.lastIndexOf('/') + 1);
                        selectedFolderPath = "/storage/" + volumeId;
                        if (!relativePath.isEmpty()) {
                            selectedFolderPath += "/" + relativePath;
                        }
                    }
                } else {
                    selectedFolderPath = path;
                }
            }

            prefs.edit().putString("working_dir", selectedFolderPath).apply();

            final String finalPath = selectedFolderPath;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (webView != null) {
                    String escaped = finalPath.replace("\\", "\\\\").replace("'", "\\'");
                    webView.evaluateJavascript("setWorkingDir('" + escaped + "')", null);
                }
            }, 500);
        }
    }

    private static final String LOADING_HTML =
        "<!DOCTYPE html><html><head>" +
        "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
        "<style>" +
        "* { margin:0; padding:0; box-sizing:border-box; }" +
        "body { background:#0a0a0a; display:flex; align-items:center;" +
        "       justify-content:center; height:100vh; font-family:monospace; }" +
        ".wrap { text-align:center; color:#666; }" +
        ".title { font-size:20px; color:#fff; margin-bottom:12px; letter-spacing:2px; }" +
        ".dot { display:inline-block; width:6px; height:6px; border-radius:50%;" +
        "       background:#666; margin:0 3px; animation:pulse 1.2s infinite; }" +
        ".dot:nth-child(2) { animation-delay:.2s; }" +
        ".dot:nth-child(3) { animation-delay:.4s; }" +
        "@keyframes pulse { 0%,80%,100%{opacity:.2} 40%{opacity:1} }" +
        "</style></head><body>" +
        "<div class='wrap'>" +
        "<div class='title'>opencode</div>" +
        "<div><span class='dot'></span><span class='dot'></span><span class='dot'></span></div>" +
        "</div></body></html>";
}