#!/usr/bin/env python3
"""A fake pagel.uz for the CI emulator (NATIVE-PLAN Phase 1): the same app API the real server has
(dashboard src/app/api/v1), with fixed data. Every request is logged to fake-pagel.log, so the smoke test can prove
the app paired, synced, listened live and sent its saved crash."""
import json, struct, sys, time, zlib
from urllib.parse import parse_qs, urlparse
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
# day; an Undo (PATCH {due: the old date, undo: true}) takes the day out (dashboard task-store.ts, habits-api.ts).
HABITS = [("t_h1", "Stretch for ten minutes", "07:30"), ("t_h2", "Read 10 pages", "21:30"), ("t_h3", "Drink water", None),
          ("t_h4", "Log food", "13:00")]
# Round B: "Log food" is done 3 times a day; each tap is one tick, the 3rd moves it on (task-store.ts habitTaps).
HABIT_TIMES = {"t_h4": 3}
HABIT_DUE = {}
HABIT_TICKS = []  # (day, id), one per tap
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
                         "priority": 2, "star": False, "due": habit_due(hid, at), "every": "day", "updated_at": "2026-10-27T10:00:00Z", "version": 1, "steps": [],
                         "times": HABIT_TIMES.get(hid, 1)}
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
            times = HABIT_TIMES.get(tid, 1)
            taps = lambda d: sum(1 for x in HABIT_TICKS if x == (d, tid))
            # A dot (round B): a day before the habit's date, or an untick, counts for that day only; the date stays.
            dot = b.get("day") and b["day"] >= (datetime.now(TASHKENT) - timedelta(days=7)).strftime("%Y-%m-%d") and \
                (b.get("done") is False or b["day"] < habit_due(tid, at)[:10])
            if dot and not moved:
                if b["done"] is True and taps(b["day"]) < times:
                    HABIT_TICKS.append((b["day"], tid))
                elif b["done"] is False and taps(b["day"]) > 0:
                    if b["day"] == day and taps(day) == times and habit_due(tid, at)[:10] > day:
                        HABIT_DUE[tid] = day + (f" {at}" if at else "")
                    HABIT_TICKS.remove((b["day"], tid))
            elif b.get("done") is True and not moved and taps(tick_day) < times:
                HABIT_TICKS.append((tick_day, tid))
                if taps(tick_day) >= times:
                    nxt = (datetime.strptime(tick_day, "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")
                    HABIT_DUE[tid] = nxt + (f" {at}" if at else "")
            if "due" in b:
                HABIT_DUE[tid] = b["due"]
                # Only an Undo (undo: true) takes the day's tick out; a new time keeps it (task-store.ts, 2026-10-28).
                if b.get("undo") is True and (day, tid) in HABIT_TICKS:
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
            n = {}
            for k in HABIT_TICKS:
                n[k] = n.get(k, 0) + 1
            return self.send(200, {"day": today(), "done_today": [h for (d, h), c in n.items() if d == today() and c >= HABIT_TIMES.get(h, 1)],
                                   "ticks": [{"day": d, "id": h, "n": c} for (d, h), c in n.items()]})
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

# ── Phase 5 · Rituals (Empty your head, Close the day, the weekly review) ──────────────────────────────────────────
# POST /api/v1/sweep splits a dump line by line (the real one asks the AI: dashboard ritual-api.ts); POST /api/v1/tasks
# gives every new task its own id (the first is still t_ci6, as the Add walk expects); POST /api/v1/rituals logs a
# ritual once per id; GET /api/v1/rituals answers with the ritual day (Tashkent; until 05:00 it is still last night).
# A removed task leaves /sync; the sweep's tasks join it, as pagel.uz would show them.
SWEEPS = {}    # id -> rows
RITUALS = []   # (action, body) in the order taken
ADDED = []     # (id, body) of POST /api/v1/tasks
REMOVED = set()
SWEEP_KINDS = [("action", "humopedia"), ("action", "me"), ("someday", "ababil")]

def ritual_day():
    return (datetime.now(TASHKENT) - timedelta(hours=5)).strftime("%Y-%m-%d")

_p4_post, _p4_patch, _p4_get, _p4_sync = H.do_POST, H.do_PATCH, H.do_GET, sync_body

def sync_body():
    s = _p4_sync()
    swept = {r["text"] for rows in SWEEPS.values() for r in rows}
    added = [{"id": tid, "project": b["project"], "phase": "Now", "phase_sort": 0, "phase_now": True, "sort": 90 + k, "text": b["text"], "status": "open",
              "priority": b.get("priority", 2), "star": False, "due": None, "updated_at": "2026-10-28T10:00:00Z", "version": 1, "steps": [], **EDITS.get(tid, {})}
             for k, (tid, b) in enumerate(ADDED) if b.get("text") in swept]
    s["tasks"] = [t for t in s["tasks"] + added if t["id"] not in REMOVED]
    return s

def _p5_post(self):
    if self.path not in ("/api/v1/sweep", "/api/v1/rituals", "/api/v1/tasks"):
        return _p4_post(self)
    body = self.rfile.read(int(self.headers.get("content-length") or 0)).decode()
    LOG.write(f"POST {self.path} {body[:300]}\n")
    if not self.authed():
        return self.send(401, {"error": "Send Authorization: Bearer <token>."})
    b = json.loads(body or "{}")
    if self.path == "/api/v1/tasks":
        if not b.get("project") or not b.get("text"):
            return self.send(400, {"error": "Send {project, text}."})
        tid = f"t_ci{6 + len(ADDED)}"
        ADDED.append((tid, b))
        return self.send(201, {"id": tid, "project": b["project"], "text": b["text"], "status": "open"})
    if self.path == "/api/v1/sweep":
        sid, dump = str(b.get("id", "")), str(b.get("dump", ""))
        if len(sid) < 6 or not dump.strip():
            return self.send(400, {"error": "Write what is on your mind first."})
        if sid not in SWEEPS:
            lines = [l.strip() for l in dump.splitlines() if l.strip()]
            SWEEPS[sid] = [{"kind": k, "text": l[:1].upper() + l[1:], "project": p} for l, (k, p) in zip(lines, SWEEP_KINDS * 10)]
        return self.send(200, {"rows": SWEEPS[sid]})
    if b.get("action") not in ("shutdown", "review") or len(str(b.get("id", ""))) < 6:
        return self.send(400, {"error": 'action is "shutdown" or "review".'})
    if any(x.get("id") == b.get("id") for _, x in RITUALS):
        return self.send(200, {"ok": True, "already": True})
    RITUALS.append((b["action"], b))
    return self.send(201, {"ok": True, "day": b.get("day")})

def _p5_patch(self):
    tid = self.path.rsplit("/", 1)[-1]
    if not any(tid == a for a, _ in ADDED) and tid not in {i for i, *_ in TODAY} | {u for u, *_ in UPCOMING}:
        return _p4_patch(self)
    body = self.rfile.read(int(self.headers.get("content-length") or 0)).decode()
    LOG.write(f"PATCH {self.path} {body[:300]} auth={self.authed()}\n")
    if not self.authed():
        return self.send(401, {"error": "Send Authorization: Bearer <token>."})
    b = json.loads(body or "{}")
    if b.get("remove") is True:
        REMOVED.add(tid)
    if b.get("restore") is True:
        REMOVED.discard(tid)
    if "done" in b:
        (DONE.add if b["done"] is True else DONE.discard)(tid)
    if "steps" in b:
        STEPS[tid] = [[t, n in set(b.get("ticked", []))] for n, t in enumerate(b["steps"])]
    for k in ("priority", "due", "title", "every", "star", "text", "done_when"):
        if k in b:
            EDITS.setdefault(tid, {})[k] = b[k]
    self.send(200, {"id": tid, "status": "dropped" if tid in REMOVED else ("done" if tid in DONE else "open"), "applied": list(b.keys())})

def _p5_get(self):
    if not self.path.startswith("/api/v1/rituals"):
        return _p4_get(self)
    LOG.write(f"GET {self.path} auth={self.authed()}\n")
    if not self.authed():
        return self.send(401, {"error": "Send Authorization: Bearer <token>."})
    reviews = [x.get("day") for a, x in RITUALS if a == "review"]
    last = max(reviews) if reviews else "2026-10-14"
    due = (datetime.strptime(ritual_day(), "%Y-%m-%d") - datetime.strptime(last, "%Y-%m-%d")).days >= 7
    return self.send(200, {"day": ritual_day(), "shut_today": any(a == "shutdown" and x.get("day") == ritual_day() for a, x in RITUALS),
                           "last_review": last, "review_due": due, "review_days": 7, "stuck": [{"project": "labs", "title": "Labs"}], "sweeps_left": 20})

H.do_POST, H.do_PATCH, H.do_GET = _p5_post, _p5_patch, _p5_get

# ── Phase 6a · the Compass (dashboard compass-api.ts): sealed items only ────────────────────────────────────────────
# PUT /api/v1/compass/items/<id> takes {kind, parent, blob, starts, ends, deleted} and nothing else: any other key (a
# title, a why…) is refused, as pagel.uz refuses it; the blob must be the phone's envelope (base64, version byte 1).
import base64
COMPASS = {}       # id -> body
COMPASS_LINKS = {}  # project -> goal id
COMPASS_RECOVERY = [None]
ITEM_KEYS = {"kind", "parent", "blob", "starts", "ends", "deleted", "base"}

def sealed(blob):
    try:
        raw = base64.b64decode(blob, validate=True)
    except Exception:
        return False
    return isinstance(blob, str) and len(raw) >= 29 and raw[0] == 1

def _compass_put(self):
    body = self.rfile.read(int(self.headers.get("content-length") or 0)).decode()
    LOG.write(f"PUT {self.path} {body[:4000]} auth={self.authed()}\n")
    if not self.authed():
        return self.send(401, {"error": "Send Authorization: Bearer <token>."})
    b = json.loads(body or "{}")
    parts = self.path.split("/")  # ['', 'api', 'v1', 'compass', what, id]
    if len(parts) < 5 or parts[3] != "compass":
        return self.send(404, {"error": "Unknown path."})
    what = parts[4]
    if what == "items" and len(parts) == 6:
        extra = set(b) - ITEM_KEYS
        if extra:
            return self.send(400, {"error": "pagel.uz never takes goal words: " + ", ".join(sorted(extra))})
        if not sealed(b.get("blob")):
            return self.send(400, {"error": "blob must be the phone's sealed envelope (base64, version 1)."})
        old = COMPASS.get(parts[5], {}).get("version", 0)
        if parts[5] in COMPASS and int(b.get("base") or 0) != old:
            return self.send(409, {"error": "A newer copy is on pagel.uz.", "version": old})
        COMPASS[parts[5]] = {**b, "id": parts[5], "version": old + 1}
        return self.send(200, {"id": parts[5], "version": old + 1})
    if what == "links" and len(parts) == 6:
        if set(b) - {"goal"}:
            return self.send(400, {"error": "Send {goal}."})
        COMPASS_LINKS[parts[5]] = b.get("goal")
        return self.send(200, {"project": parts[5], "goal": b.get("goal")})
    if what == "recovery":
        if b.get("blob") is not None and not sealed(b.get("blob")):
            return self.send(400, {"error": "blob must be sealed."})
        COMPASS_RECOVERY[0] = b.get("blob")
        return self.send(200, {"recovery": b.get("blob") is not None})
    return self.send(404, {"error": "Unknown path."})

_p5_get_compass = H.do_GET
def _compass_get(self):
    if self.path != "/api/v1/compass":
        return _p5_get_compass(self)
    LOG.write(f"GET {self.path} auth={self.authed()}\n")
    if not self.authed():
        return self.send(401, {"error": "Send Authorization: Bearer <token>."})
    return self.send(200, {"items": list(COMPASS.values()), "links": [{"project": k, "goal": v} for k, v in COMPASS_LINKS.items() if v],
                           "recovery": COMPASS_RECOVERY[0]})

H.do_PUT, H.do_GET = _compass_put, _compass_get
# ── Round D · Namoz: the five prayers the phone adds (POST /api/v1/tasks {group: "namoz", every: "day"}) stay in /sync
# as daily habits with their group, as pagel.uz keeps them (dashboard task-store.ts `group`).
_p5_sync = sync_body

def sync_body():
    s = _p5_sync()
    s["tasks"] += [{"id": tid, "project": b["project"], "phase": "Now", "phase_sort": 0, "phase_now": True, "sort": 120 + k, "text": b["text"], "status": "open",
                    "priority": b.get("priority", 2), "star": False, "due": b.get("due"), "every": b.get("every"), "group": b.get("group"),
                    "updated_at": "2026-10-29T10:00:00Z", "version": 1, "steps": []}
                   for k, (tid, b) in enumerate(ADDED) if b.get("group") == "namoz" and tid not in REMOVED]
    return s

# ── Round D item 2 · GET /api/v1/notes: the projects with quick notes, and one project's notes (dashboard quick-notes-api.ts).
NOTES = {"ababil": [{"text": "RunCam Phoenix 2: the night camera", "link": "https://example.com/p", "picture": None, "day": "2026-10-28"}]}
_rd_get = H.do_GET

def _rd2_get(self):
    if not self.path.startswith("/api/v1/notes"):
        return _rd_get(self)
    LOG.write(f"GET {self.path} auth={self.authed()}\n")
    if not self.authed():
        return self.send(401, {"error": "Send Authorization: Bearer <token>."})
    q = parse_qs(urlparse(self.path).query)
    p = (q.get("project") or [None])[0]
    if p is None:
        return self.send(200, {"projects": [{"project": k, "count": len(v), "last": v[0]["day"]} for k, v in NOTES.items()]})
    return self.send(200, {"project": p, "notes": NOTES.get(p, [])})

H.do_GET = _rd2_get


# ── Core round · projects you shape (dashboard projects-api.ts) and the KPI counts. A project the phone makes or edits
# lives here; PATCH /tasks/<id> {project, sort, milestone} moves a task (EDITS feeds /sync); merge holds nothing (the
# phone holds it for Undo); KPI counts are only logged.
import io
PROJ = {}   # name -> the fields the phone set
MADE = []   # names the phone created, in order
_core_post, _core_patch, _core_get, _core_sync = H.do_POST, H.do_PATCH, H.do_GET, sync_body

def _task_project(tid, default):
    return EDITS.get(tid, {}).get("project", default)

def sync_body():
    s = _core_sync()
    known = {t["id"] for t in s["tasks"]}
    # Tasks the phone added into a project it made stay in /sync (a real server keeps them).
    s["tasks"] += [{"id": tid, "project": b["project"], "phase": "Now", "phase_sort": 0, "phase_now": True, "sort": 200 + k, "text": b["text"], "status": "done" if tid in DONE else "open",
                    "priority": b.get("priority", 2), "star": False, "due": b.get("due"), "every": None, "updated_at": "2026-10-29T10:00:00Z", "version": 1, "steps": [], **EDITS.get(tid, {})}
                   for k, (tid, b) in enumerate(ADDED) if b.get("project") in MADE and tid not in known and tid not in REMOVED]
    for t in s["tasks"]:
        t.update({k: v for k, v in EDITS.get(t["id"], {}).items() if k in ("project", "sort", "milestone")})
    projects = [dict(p) for p in s["projects"]] + [{"name": n, "title": PROJ[n].get("title", n), "color": None, "sort": 100 + i, "open": 0} for i, n in enumerate(MADE)]
    for p in projects:
        p.update({k: v for k, v in PROJ.get(p["name"], {}).items() if k not in ("deleted", "finished")})
    s["projects"] = [p for p in projects if not PROJ.get(p["name"], {}).get("deleted")]
    return s

def _core_post_h(self):
    if not (self.path == "/api/v1/projects" or self.path.startswith("/api/v1/projects/") or self.path == "/api/v1/kpi"):
        return _core_post(self)
    body = self.rfile.read(int(self.headers.get("content-length") or 0)).decode()
    LOG.write(f"POST {self.path} {body[:300]} auth={self.authed()}\n")
    if not self.authed():
        return self.send(401, {"error": "Send Authorization: Bearer <token>."})
    b = json.loads(body or "{}")
    if self.path == "/api/v1/kpi":
        return self.send(200, {"ok": True})
    if self.path == "/api/v1/projects":
        n = b.get("name") or "p-new"
        if n not in MADE:
            MADE.append(n)
        PROJ.setdefault(n, {}).update({k: b[k] for k in ("title", "color", "icon", "area") if k in b})
        return self.send(201, {"project": {"name": n, **PROJ[n]}})
    name, _, what = self.path[len("/api/v1/projects/"):].partition("/")
    if what == "merge":
        into = b.get("into")
        moved = [i for i, p, *_ in TODAY + UPCOMING if _task_project(i, p) == name] + [t for t, a in ADDED if _task_project(t, a.get("project")) == name]
        for i in moved:
            EDITS.setdefault(i, {})["project"] = into
        PROJ.setdefault(name, {})["archived"] = True
        return self.send(200, {"moved": moved, "into": into})
    return self.send(404, {"error": "Unknown path."})

def _core_patch_h(self):
    raw = self.rfile.read(int(self.headers.get("content-length") or 0))
    if self.path.startswith("/api/v1/projects/"):
        LOG.write(f"PATCH {self.path} {raw.decode()[:300]} auth={self.authed()}\n")
        if not self.authed():
            return self.send(401, {"error": "Send Authorization: Bearer <token>."})
        b = json.loads(raw or b"{}")
        name = self.path.rsplit("/", 1)[-1]
        PROJ.setdefault(name, {}).update(b)
        if b.get("finished") is True:
            PROJ[name]["finished_at"] = "2026-10-29T12:00:00Z"
        return self.send(200, {"project": {"name": name}, "applied": list(b.keys())})
    b = json.loads(raw or b"{}")
    extra = {k: b.pop(k) for k in ("project", "sort", "milestone") if k in b}
    tid = self.path.rsplit("/", 1)[-1]
    if extra and not b:
        LOG.write(f"PATCH {self.path} {raw.decode()[:300]} auth={self.authed()}\n")
        if not self.authed():
            return self.send(401, {"error": "Send Authorization: Bearer <token>."})
        EDITS.setdefault(tid, {}).update(extra)
        return self.send(200, {"id": tid, "status": "open", "applied": list(extra.keys())})
    EDITS.setdefault(tid, {}).update(extra)
    self.rfile = io.BytesIO(raw)
    return _core_patch(self)

def _core_get_h(self):
    if not self.path.startswith("/api/v1/kpi/week"):
        return _core_get(self)
    LOG.write(f"GET {self.path} auth={self.authed()}\n")
    days = [{"day": day_from_today(i - 6), "done": {"phone": i, "web": 1, "telegram": 0, "other": 0}, "opens": 1 if i % 2 else 0, "first_tick_ms": 95000 if i == 6 else None} for i in range(7)]
    return self.send(200, {"days": days, "projects_finished": 1, "days_opened": 3, "done": 28})

H.do_POST, H.do_PATCH, H.do_GET = _core_post_h, _core_patch_h, _core_get_h

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8799), H).serve_forever()
