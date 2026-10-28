#!/usr/bin/env python3
"""A fake pagel.uz for the CI emulator (NATIVE-PLAN Phase 1): the same app API the real server has
(dashboard src/app/api/v1), with fixed data. Every request is logged to fake-pagel.log, so the smoke test can prove
the app paired, synced, listened live and sent its saved crash."""
import json, struct, sys, time, zlib
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# pagel.uz counts its days in Tashkent (UTC+5, no summer time), as inbox-api.ts and views.ts do. The runner's own clock
# is UTC: a run between 19:00 and 24:00 UTC once sent "yesterday" and the phone reset its ring (checker 2026-10-28 #3).
TASHKENT = timezone(timedelta(hours=5))
def today():
    return datetime.now(TASHKENT).strftime("%Y-%m-%d")

CODE, TOKEN = "482913", "ci-device-token"
LOG = open(sys.argv[2] if len(sys.argv) > 2 else "fake-pagel.log", "a", buffering=1)
PROJECTS = [("humopedia", "Humopedia", 41), ("ababil", "ABABIL", 27), ("pagel", "PAGEL", 58), ("uzcosmos", "UzCosmos", 4), ("me", "Me", 12), ("labs", "Labs", 6)]

# Today's five stars, dealt in this order by /api/v1/today (the app shows 3 at a time; the 4th comes after a tick).
TODAY = [("t_ci1", "pagel", "Ship the native app", 0, "2026-10-27 15:00"), ("t_ci2", "me", "Call the bank about the report", 1, None),
         ("t_ci3", "humopedia", "Record the fractions lesson", 2, None), ("t_ci4", "ababil", "Measure the drone's hover time", 1, None),
         ("t_ci5", "me", "Weigh in", 3, None)]
DONE = set()
# Panel edits the app sent (Phase 1), applied like pagel.uz does, so the next sync shows them.
STEPS = {"t_ci2": [["Find the account number", False], ["Call at 10", False]]}
EDITS = {}

# The Inbox (Phase 2): saves as GET /api/v1/inbox deals them; POST /api/v1/inbox takes one choice (dashboard inbox-api.ts).
# The walkthrough sorts ib1..ib5 offline: swipe left (drop), swipe up (later), swipe right (the AI's drop), the AI's Action,
# the AI's Note; the asserts read the POSTs below in this order.
INBOX = [
    ("ib1", "https://www.instagram.com/p/ci1/", "10 AI tools lists are mostly ads", "not tied to your work", "all", "drop", "", "", "ai.daily", True),
    ("ib2", "https://x.com/karpathy/status/1", "Quizzes beat rereading: recall builds memory", "Every Humopedia lesson can end with 3 questions", "humopedia", "learn", "", "", "karpathy", False),
    ("ib3", "https://www.instagram.com/p/ci3/", "A meme about Mondays", "not tied to your work", "all", "drop", "", "", "memes.uz", True),
    ("ib4", "https://www.linkedin.com/posts/grant-1", "The IT Park grant round closes on 15 November", "Humopedia can apply for the startup grant", "humopedia", "action", "Open the grant form and fill page one", "", "IT Park", False),
    ("ib5", "https://www.instagram.com/p/ci5/", "An Uzbek maker builds FPV drones with an ESP32", "Ideas for ABABIL and the drone lessons", "ababil", "note", "", "@ixtirochi_bola: Uzbek maker, FPV drones with ESP32", "ixtirochi_bola", True),
    ("ib6", "https://example.com/post", "Spaced repetition apps keep 90 % after a month", "", "humopedia", "learn", "", "", "", False),
    ("ib7", "", "Buy a second battery for the A05s", "", "me", "action", "Order the battery on Uzum", "", "", False),
    ("ib8", "https://x.com/a/status/8", "Unread save from last night", "", "all", "learn", "", "", "", False),
]
SORTS = []  # (file, action) in the order pagel.uz took them
LATER = set()
TRIAGED = set()  # saves the AI has read since the phone asked (POST {action: "triage"})

# Habits (Phase 3): repeating tasks in /sync, due today; a tick (PATCH done:true) moves the date a day on and counts the
# day; an Undo (PATCH due: the old date) takes the day out (dashboard task-store.ts, habits-api.ts).
HABITS = [("t_h1", "Stretch for ten minutes", "07:30"), ("t_h2", "Read 10 pages", "21:30"), ("t_h3", "Drink water", None)]
HABIT_DUE = {}
HABIT_TICKS = []  # (day, id)
def habit_due(hid, at):
    return HABIT_DUE.get(hid, today() + (f" {at}" if at else ""))

def day_from_today(n):
    return (datetime.now(TASHKENT) + timedelta(days=n)).strftime("%Y-%m-%d")

# Upcoming (Phase 4): two overdue tasks and one for tomorrow, none starred (they are not on Today). The walkthrough drags
# t_u3 onto the day after tomorrow and moves the overdue ones to today; PATCH {due} lands in EDITS like a panel edit.
UPCOMING = [("t_u1", "humopedia", "Send the grant form", 1, -1), ("t_u2", "me", "Pay the internet bill", 0, -3), ("t_u3", "humopedia", "Review the lesson draft", 2, 1)]
# Completed (Phase 4): finished tasks with done_at (UTC instants); /sync carries the last 14 days, /api/v1/completed all.
# The walkthrough unticks t_c1 (PATCH {done: false}) and it opens again.
def ago(hours):
    return (datetime.now(timezone.utc) - timedelta(hours=hours)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
COMPLETED = [("t_c1", "me", "Renew the domain", 1), ("t_c2", "humopedia", "Write the IT Park report", 26), ("t_c3", "ababil", "Export the hover log", 24 * 20)]
DONE_AT = {cid: ago(h) for cid, _, _, h in COMPLETED}
REOPENED = set()

def completed_rows(recent_only):
    rows = []
    for k, (cid, proj, text, h) in enumerate(COMPLETED):
        if cid in REOPENED or (recent_only and h > 24 * 14):
            continue
        rows.append({"id": cid, "project": proj, "phase": "Now", "phase_sort": 0, "phase_now": True, "sort": 80 + k, "text": text, "status": "done",
                     "priority": 2, "star": False, "due": None, "done_at": DONE_AT[cid], "updated_at": DONE_AT[cid], "version": 2, "steps": [],
                     "day": datetime.fromisoformat(DONE_AT[cid].replace("Z", "+00:00")).astimezone(TASHKENT).strftime("%Y-%m-%d")})
    return rows

def inbox_body():
    sorted_files = {f for f, a in SORTS if a != "later"}
    items = [{"file": f"inbox/{i}.md", "saved": "2026-10-27", "source": src, "text": f"Instagram post by @{au} posted 2026-10-27\n\n{claim}. The caption goes on for a while about it, with more words than the card shows." if "instagram" in src else f"{claim}. The post says more about it.",
              "claim": claim, "why": why, "project": proj, "kind": kind, "next": nxt, "note": note, "guessed": i != "ib8" or f"inbox/{i}.md" in TRIAGED, "author": au, "posted": "2026-10-27" if au else "",
              "later": "2026-10-28" if f"inbox/{i}.md" in LATER else None, "has_picture": pic}
             for i, src, claim, why, proj, kind, nxt, note, au, pic in INBOX if f"inbox/{i}.md" not in sorted_files]
    items.sort(key=lambda x: x["later"] is not None)
    return {"day": today(), "goal": 20, "sorted": 12 + len(SORTS), "kept_today": 2 + sum(1 for _, a in SORTS if a == "keep"), "cap": 5, "min_words": 6,
            "total": len(items), "items": items}

def png(w=360, h=240):
    # A mint-to-violet gradient, written by hand (no Pillow on the runner): the card's "post picture".
    rows = b"".join(b"\x00" + bytes(v for x in range(w) for v in (int(40 + 150 * x / w), int(160 - 60 * y / h), int(140 + 100 * y / h))) for y in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
PICTURE = png()

def sync_body():
    return {"cursor": 10, "full": True,
            "projects": [{"name": n, "title": t, "color": None, "sort": i, "open": o} for i, (n, t, o) in enumerate(PROJECTS)],
            "tasks": [{"id": i, "project": p, "phase": "Now", "phase_sort": 0, "phase_now": True, "sort": k, "text": text, "status": "done" if i in DONE else "open",
                       "priority": pr, "star": True, "due": due, "updated_at": "2026-10-27T10:00:00Z", "version": 1,
                       "steps": [{"text": t, "done": d} for t, d in STEPS.get(i, [])], **EDITS.get(i, {})}
                      for k, (i, p, text, pr, due) in enumerate(TODAY)]
                     + [{"id": hid, "project": "me", "phase": "Now", "phase_sort": 0, "phase_now": True, "sort": 50 + k, "text": text, "status": "open",
                         "priority": 2, "star": False, "due": habit_due(hid, at), "every": "day", "updated_at": "2026-10-27T10:00:00Z", "version": 1, "steps": []}
                        for k, (hid, text, at) in enumerate(HABITS)]
                     + [{"id": uid, "project": p, "phase": "Now", "phase_sort": 0, "phase_now": True, "sort": 60 + k, "text": text, "status": "open",
                         "priority": pr, "star": False, "due": day_from_today(n), "updated_at": "2026-10-27T10:00:00Z", "version": 1, "steps": [], **EDITS.get(uid, {})}
                        for k, (uid, p, text, pr, n) in enumerate(UPCOMING)]
                     + completed_rows(recent_only=True)
                     + [{"id": cid, "project": p, "phase": "Now", "phase_sort": 0, "phase_now": True, "sort": 80, "text": text, "status": "open",
                         "priority": 2, "star": False, "due": None, "updated_at": "2026-10-28T10:00:00Z", "version": 3, "steps": []}
                        for cid, p, text, _ in COMPLETED if cid in REOPENED],
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
        # One answer per connection: a kept-alive socket would outlive "adb reverse --remove" and leak the walkthrough's offline sorts.
        self.send_header("connection", "close")
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
        if self.path == "/api/v1/tasks":
            if not self.authed():
                return self.send(401, {"error": "Send Authorization: Bearer <token>."})
            b = json.loads(body or "{}")
            if not b.get("project") or not b.get("text"):
                return self.send(400, {"error": "Send {project, text}."})
            return self.send(201, {"id": "t_ci6", "project": b["project"], "text": b["text"], "status": "open"})
        if self.path == "/api/v1/inbox":
            if not self.authed():
                return self.send(401, {"error": "Send Authorization: Bearer <token>."})
            b = json.loads(body or "{}")
            if b.get("action") == "triage":
                files = [f for f in b.get("files", []) if isinstance(f, str) and f.startswith("inbox/")]
                TRIAGED.update(files)
                return self.send(202, {"started": True, "files": len(files)})
            f, a = b.get("file", ""), b.get("action", "")
            if a not in ("keep", "act", "note", "later", "drop", "now") or not f.startswith("inbox/"):
                return self.send(400, {"error": "action is one of keep, act, note, later, drop, now."})
            if a == "keep" and len((b.get("sentence") or "").split()) < 6:
                return self.send(400, {"error": "Write it in your words: 6+ words."})
            if (f, a) in SORTS:
                return self.send(200, {"ok": True, "applied": a, "already": True, "sorted": 12 + len(SORTS)})
            SORTS.append((f, a))
            if a == "later":
                LATER.add(f)
            return self.send(200, {"ok": True, "applied": a, "sorted": 12 + len(SORTS), "kept_today": 2 + sum(1 for _, x in SORTS if x == "keep")})
        if self.path == "/api/v1/app/crash":
            return self.send(201, {"ok": True}) if self.authed() else self.send(401, {"error": "Send Authorization: Bearer <token>."})
        self.send(404, {"error": "Unknown path."})

    def do_PATCH(self):
        body = self.rfile.read(int(self.headers.get("content-length") or 0)).decode()
        LOG.write(f"PATCH {self.path} {body[:300]} auth={self.authed()}\n")
        if not self.authed():
            return self.send(401, {"error": "Send Authorization: Bearer <token>."})
        tid = self.path.rsplit("/", 1)[-1]
        habits = {h for h, *_ in HABITS}
        known = {i for i, *_ in TODAY} | habits | {u for u, *_ in UPCOMING} | {c for c, *_ in COMPLETED}
        if not self.path.startswith("/api/v1/tasks/") or tid not in known:
            return self.send(404, {"error": "No such task."})
        b = json.loads(body or "{}")
        if tid in habits:
            at = next(t for h, _, t in HABITS if h == tid)
            day = today()
            # The phone says the day it ticked and the date it moved from (task-api.ts): a tick sent after midnight counts
            # for its own day, and one the web already counted (the date moved) does not move the habit again.
            yesterday = (datetime.now(TASHKENT) - timedelta(days=1)).strftime("%Y-%m-%d")
            tick_day = b.get("day") if b.get("day") in (day, yesterday) else day
            moved = "if_due" in b and b["if_due"] != habit_due(tid, at)
            if b.get("done") is True and not moved:
                HABIT_TICKS.append((tick_day, tid))
                nxt = (datetime.strptime(tick_day, "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")
                HABIT_DUE[tid] = nxt + (f" {at}" if at else "")
            if "due" in b:
                HABIT_DUE[tid] = b["due"]
                if b["due"] and b["due"][:10] <= day and (day, tid) in HABIT_TICKS:
                    HABIT_TICKS.remove((day, tid))
            return self.send(200, {"id": tid, "status": "open", "due": HABIT_DUE.get(tid), "applied": list(b.keys())})
        if tid in DONE_AT and "done" in b:
            (REOPENED.add if b["done"] is False else REOPENED.discard)(tid)
            return self.send(200, {"id": tid, "status": "open" if tid in REOPENED else "done", "applied": list(b.keys())})
        if "done" in b:
            (DONE.add if b["done"] is True else DONE.discard)(tid)
        if "steps" in b:
            ticked = set(b.get("ticked", []))
            STEPS[tid] = [[t, n in ticked] for n, t in enumerate(b["steps"])]
        for k in ("priority", "due", "title", "every", "star", "text", "done_when"):
            if k in b:
                EDITS.setdefault(tid, {})[k] = b[k]
        # The new pagel.uz names the fields it understood (task-api.ts `applied`).
        self.send(200, {"id": tid, "status": "done" if tid in DONE else "open", "applied": list(b.keys())})

    def do_GET(self):
        LOG.write(f"GET {self.path} auth={self.authed()}\n")
        if not self.authed():
            return self.send(401, {"error": "Send Authorization: Bearer <token>."})
        if self.path == "/api/v1/today":
            return self.send(200, {"day": "2026-10-27", "shown_at_once": 3, "tasks": [{"id": i} for i, *_ in TODAY if i not in DONE]})
        if self.path.startswith("/api/v1/habits"):
            return self.send(200, {"day": today(), "done_today": [h for d, h in HABIT_TICKS if d == today()], "ticks": [{"day": d, "id": h} for d, h in HABIT_TICKS]})
        if self.path.startswith("/api/v1/inbox/picture"):
            if any(f"{i}.md" in self.path and pic for i, *_, pic in INBOX):
                self.send_response(200)
                self.send_header("content-type", "image/png")
                self.send_header("content-length", str(len(PICTURE)))
                self.send_header("connection", "close")
                self.end_headers()
                self.wfile.write(PICTURE)
                return
            return self.send(404, {"error": "No picture."})
        if self.path.startswith("/api/v1/inbox"):
            return self.send(200, inbox_body())
        if self.path.startswith("/api/v1/completed"):
            # total = every finished task, as the web counts them; 4 were finished before done_at was kept (no row).
            return self.send(200, {"tasks": completed_rows(recent_only=False), "before": None, "total": len(completed_rows(recent_only=False)) + 4})
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
