from python.app import app, HOST, PORT

def run():
    """Called by Android MainActivity to start the Flask server."""
    print(f"OpenCode - http://localhost:{PORT}")
    # debug=False is critical on Android — debug mode forks the process
    # which breaks Chaquopy's Python environment
    app.run(host=HOST, port=PORT, debug=False, use_reloader=False, threaded=True)

if __name__ == "__main__":
    run()
