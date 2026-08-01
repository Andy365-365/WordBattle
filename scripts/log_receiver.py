#!/usr/bin/env python3
"""Simple log receiver for WordBattle debug logs."""
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
from datetime import datetime
import sys
import os

LOG_DIR = "/data/wordbattle/logs"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            length = int(self.headers.get('Content-Length', 0))
            data = self.rfile.read(length).decode('utf-8', errors='replace')
            try:
                entry = json.loads(data)
                device = entry.get("device", "unknown")
                devId = entry.get("devId", "")
                level = entry.get("level", "I")
                msg = entry.get("msg", "")
                version = entry.get("version", "")
            except json.JSONDecodeError:
                device = "unknown"
                devId = ""
                level = "?"
                msg = data
                version = ""
            ts = datetime.now().strftime("%H:%M:%S")
            devLabel = f"[{devId}]" if devId else ""
            line = f"[{ts}] {version} [{device}][{devId}] [{level}] {msg}\n"
            os.makedirs(LOG_DIR, exist_ok=True)
            with open(os.path.join(LOG_DIR, "remote_all.log"), "a") as f:
                f.write(line)
            with open(os.path.join(LOG_DIR, f"remote_{device}_{level}.log"), "a") as f:
                f.write(line)
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(b'ok')
        except Exception as e:
            self.send_response(500)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(str(e).encode())

    def log_message(self, format, *args):
        pass

if __name__ == "__main__":
    os.makedirs(LOG_DIR, exist_ok=True)
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Log receiver on :{PORT}", flush=True)
    server.serve_forever()