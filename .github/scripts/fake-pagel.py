#!/usr/bin/env python3
"""A fake pagel.uz for the CI emulator (NATIVE-PLAN Phase 1): the same app API the real server has
(dashboard src/app/api/v1), with fixed data. Every request is logged to fake-pagel.log, so the smoke test can prove
the app paired, synced, listened live and sent its saved crash."""
import json, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CODE, TOKEN = "482913", "ci-device-token"
LOG = open(sys.argv[2] if len(sys.argv) > 2 else "fake-pagel.log", "a", buffering=1)
PROJECTS = [("humopedia", "Humopedia", 41), ("ababil", "ABABIL", 27), ("pagel", "PAGEL", 58), ("uzcosmos", "UzCosmos", 4), ("me", "Me", 12), ("labs", "Labs", 6)]

def sync_body():
    return {"cursor": 10, "full": True,
            "projects": [{"name": n, "title": t, "color": None, "sort": i, "open": o} for i, (n, t, o) in enumerate(PROJECTS)],
            "tasks": [{"id": "t_ci1", "project": "pagel", "phase": "Now", "sort": 1, "text": "Ship the native app", "status": "open",
                       "priority": 1, "star": True, "updated_at": "2026-10-27T10:00:00Z", "version": 1, "steps": []}],
            "removed": []}

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def authed(self):
        return self.headers.get("authorization") == f"Bearer {TOKEN}"

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("content-length") or 0)).decode()
        LOG.write(f"POST {self.path} {body[:300]}\n")
        if self.path == "/api/v1/devices/pair":
            code = json.loads(body or "{}").get("code")
            return self.send(201, {"id": "d_ci", "name": "CI emulator", "token": TOKEN}) if code == CODE else self.send(401, {"error": "Wrong code. 4 tries left."})
        if self.path == "/api/v1/app/crash":
            return self.send(201, {"ok": True}) if self.authed() else self.send(401, {"error": "Send Authorization: Bearer <token>."})
        self.send(404, {"error": "Unknown path."})

    def do_GET(self):
        LOG.write(f"GET {self.path} auth={self.authed()}\n")
        if not self.authed():
            return self.send(401, {"error": "Send Authorization: Bearer <token>."})
        if self.path.startswith("/api/v1/sync"):
            return self.send(200, sync_body())
        if self.path.startswith("/api/v1/stream"):
            self.send_response(200)
            self.send_header("content-type", "text/event-stream")
            self.end_headers()
            try:
                self.wfile.write(b"retry: 5000\nevent: hello\ndata: 10\n\n")
                self.wfile.flush()
                for _ in range(60):
                    time.sleep(1)
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            self.close_connection = True
            return
        self.send(404, {"error": "Unknown path."})

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8799), H).serve_forever()
