#!/usr/bin/env python3
"""Videos - Linux capture receiver and playback server.

Receives screen-capture MP4 uploads from the Android capture app over HTTP
and plays them back through VLC.

Endpoints:
    GET  /health   -> {"status": "ok"}
    POST /upload   -> raw body saved as the file given by ?name=<filename>
    POST /stop     -> stop the current playback
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

CAPTURE_DIR: Path = Path.home() / "Videos" / "captures"

PLAYBACK_LOCK = threading.Lock()
CURRENT_PROCESS: subprocess.Popen | None = None


def sanitize_name(name: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", name).strip("._")
    return cleaned or "capture.mp4"


def stop_current() -> None:
    global CURRENT_PROCESS
    process = CURRENT_PROCESS
    CURRENT_PROCESS = None
    if process is not None and process.poll() is None:
        process.terminate()


def play(path: Path) -> None:
    global CURRENT_PROCESS
    stop_current()
    with PLAYBACK_LOCK:
        CURRENT_PROCESS = subprocess.Popen(
            ["vlc", "--play-and-exit", "--quiet", str(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )


class CaptureHandler(BaseHTTPRequestHandler):
    server_version = "VideosLinux/1.0"

    def _send_json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._send_json(200, {"status": "ok"})
        else:
            self._send_json(404, {"status": "not_found"})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/stop":
            stop_current()
            self._send_json(200, {"status": "stopped"})
            return
        if parsed.path == "/play":
            name = sanitize_name((parse_qs(parsed.query).get("name") or [""])[0])
            CAPTURE_DIR.mkdir(parents=True, exist_ok=True)
            target = CAPTURE_DIR / name
            if not target.is_file():
                self._send_json(404, {"status": "not_found", "path": str(target)})
                return
            play(target)
            self._send_json(200, {"status": "ok", "path": str(target)})
            return
        if parsed.path != "/upload":
            self._send_json(404, {"status": "not_found"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            data = self.rfile.read(length) if length > 0 else b""
            name = sanitize_name((parse_qs(parsed.query).get("name") or ["capture.mp4"])[0])
            CAPTURE_DIR.mkdir(parents=True, exist_ok=True)
            target = CAPTURE_DIR / name
            target.write_bytes(data)
            play(target)
            self._send_json(200, {"status": "ok", "path": str(target)})
        except Exception as exc:
            self._send_json(500, {"status": "error", "message": str(exc)})

    def log_message(self, format: str, *args: object) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), format % args))


def main() -> None:
    global CAPTURE_DIR
    parser = argparse.ArgumentParser(description="Videos Linux capture receiver/playback server")
    parser.add_argument("--host", default="0.0.0.0", help="interface to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000, help="port to listen on (default: 8000)")
    parser.add_argument("--capture-dir", type=Path, default=CAPTURE_DIR, help="directory to store captures")
    args = parser.parse_args()

    CAPTURE_DIR = args.capture_dir

    server = ThreadingHTTPServer((args.host, args.port), CaptureHandler)
    server.daemon_threads = True
    print(f"Videos Linux server listening on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop_current()
        server.server_close()


if __name__ == "__main__":
    main()
