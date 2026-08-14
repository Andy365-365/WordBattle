#!/usr/bin/env python3
"""Simple log receiver for WordBattle debug logs."""
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
from datetime import datetime
import sys
import os
import glob
import threading

LOG_DIR = "/data/wordbattle/logs"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
KEEP_COUNT = 7  # Keep latest N daily log files

# Prefixes of daily log files
DAILY_PREFIXES = [
    "remote_all",
    "remote_host_I", "remote_host_D", "remote_host_W", "remote_host_E",
    "remote_client_I", "remote_client_D", "remote_client_W", "remote_client_E",
]


def prune_old_logs():
    """Keep only the latest KEEP_COUNT files per prefix, delete older ones."""
    for prefix in DAILY_PREFIXES:
        pattern = os.path.join(LOG_DIR, f"{prefix}_*.log")
        files = sorted(glob.glob(pattern), reverse=True)
        for old_file in files[KEEP_COUNT:]:
            try:
                os.remove(old_file)
            except OSError:
                pass


def get_daily_paths():
    """Return dict: key -> daily log file path for today."""
    date_str = datetime.now().strftime("%Y%m%d")
    return {
        "all": os.path.join(LOG_DIR, f"remote_all_{date_str}.log"),
        "host_I": os.path.join(LOG_DIR, f"remote_host_I_{date_str}.log"),
        "host_D": os.path.join(LOG_DIR, f"remote_host_D_{date_str}.log"),
        "host_W": os.path.join(LOG_DIR, f"remote_host_W_{date_str}.log"),
        "host_E": os.path.join(LOG_DIR, f"remote_host_E_{date_str}.log"),
        "client_I": os.path.join(LOG_DIR, f"remote_client_I_{date_str}.log"),
        "client_D": os.path.join(LOG_DIR, f"remote_client_D_{date_str}.log"),
        "client_W": os.path.join(LOG_DIR, f"remote_client_W_{date_str}.log"),
        "client_E": os.path.join(LOG_DIR, f"remote_client_E_{date_str}.log"),
    }


# Prune on startup
os.makedirs(LOG_DIR, exist_ok=True)
prune_old_logs()

daily_paths = get_daily_paths()

# Global counter for SCAN lines
_scan_lock = threading.Lock()
_scan_count = 0
_scan_latest = ""


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
            line = f"[{ts}] {version} [{device}][{devLabel}] [{level}] {msg}\n"

            # Write to daily files
            all_path = daily_paths["all"]
            key_path = daily_paths.get(f"{device}_{level}")

            with open(all_path, "a") as f:
                f.write(line)
            if key_path:
                with open(key_path, "a") as f:
                    f.write(line)

            # Track SCAN lines
            if "[SCAN]" in msg:
                global _scan_count, _scan_latest
                with _scan_lock:
                    _scan_count += 1
                    _scan_latest = line.rstrip("\n")

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

    def do_GET(self):
        """Query interface for scan script."""
        if self.path.startswith("/scan/latest"):
            with _scan_lock:
                count = _scan_count
                latest = _scan_latest
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({"count": count, "latest": latest}).encode())
        elif self.path.startswith("/scan/count"):
            with _scan_lock:
                count = _scan_count
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(str(count).encode())
        else:
            self.send_response(404)
            self.end_headers()


if __name__ == "__main__":
    os.makedirs(LOG_DIR, exist_ok=True)
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Log receiver on :{PORT}", flush=True)
    server.serve_forever()