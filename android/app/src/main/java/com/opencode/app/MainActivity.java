package com.opencode.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

public class MainActivity extends Activity {

    private WebView webView;
    private static final int FLASK_PORT = 5000;
    private static final String FLASK_URL = "http://localhost:" + FLASK_PORT;
    private static final int SERVER_START_DELAY_MS = 2000;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        setupWebView();

        // Show loading while server starts
        webView.loadData(LOADING_HTML, "text/html", "UTF-8");

        // Start Flask server in background thread
        startFlaskServer();

        // Load the app after giving Flask time to start
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            webView.loadUrl(FLASK_URL);
        }, SERVER_START_DELAY_MS);
    }

    private void startFlaskServer() {
        Thread serverThread = new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                Python py = Python.getInstance();

                // Add the opencode_out parent dir to sys.path so
                // "from python.config import ..." works correctly
                PyObject sys = py.getModule("sys");
                sys.get("path").callAttr("insert", 0, "opencode_out");

                // Run Flask
                PyObject runner = py.getModule("opencode_out.runner");
                runner.callAttr("run");

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(this, "Server error: " + e.getMessage(),
                        Toast.LENGTH_LONG).show()
                );
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Prevent WebView from opening links in external browser
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("http://localhost")) {
                    return false; // let WebView handle it
                }
                return true; // block external URLs
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private static final String LOADING_HTML =
        "<!DOCTYPE html><html><head>" +
        "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
        "<style>" +
        "* { margin:0; padding:0; box-sizing:border-box; }" +
        "body { background:#0a0a0a; display:flex; align-items:center; " +
        "       justify-content:center; height:100vh; font-family:monospace; }" +
        ".wrap { text-align:center; color:#666; }" +
        ".title { font-size:20px; color:#fff; margin-bottom:12px; letter-spacing:2px; }" +
        ".dot { display:inline-block; width:6px; height:6px; border-radius:50%; " +
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
