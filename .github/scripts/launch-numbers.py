#!/usr/bin/env python3
"""One line of launch numbers for a cold start, from `am start -W` and the logcat that followed it.

usage: launch-numbers.py LABEL AM_START_TXT LOGCAT_TXT  (prints one line; exit 0)

TotalTime   Android's own "launch took" (am start -W), ms.
first-issue the first frame's render-thread time issuing draw commands (Davey IssueDrawCommandsStart -> SwapBuffers),
            where a shader is built the first time it is used. CI 40656925148: 7,055 ms on the cold Today = the ANR.
max-issue   the longest such time in any frame of the app in this logcat.
skipped     the largest "Skipped N frames" of the app's main thread (N x 16.7 ms it could not draw or take input), up to
            the glass coming on ("launch") and over the whole log ("all").
glass-on    when GlassStage turned the glass on after the flat first frame (PagelGlass line), ms after the screen was ready.
anr         whether "ANR in org.pagel.ai" appears for this launch's process.
"""
import re
import sys

label, am_path, log_path = sys.argv[1:4]
am = open(am_path, errors="ignore").read()
total = re.search(r"TotalTime: (\d+)", am)
total = int(total.group(1)) if total else -1
log = open(log_path, errors="ignore").read().splitlines()

pids = re.findall(r"Start proc (\d+):org\.pagel\.ai/", "\n".join(log))
pid = pids[-1] if pids else None
first_issue, max_issue, skipped, skipped_launch, glass_on = -1, 0, 0, 0, "-"
for line in log:
    parts = line.split()
    if len(parts) < 4 or parts[2] != pid:
        continue
    if "Davey!" in line:
        d = dict(re.findall(r"(\w+)=(\d+)", line))
        if "IssueDrawCommandsStart" in d and "SwapBuffers" in d:
            issue = (int(d["SwapBuffers"]) - int(d["IssueDrawCommandsStart"])) // 1_000_000
            if d.get("Flags") == "1" and first_issue < 0:
                first_issue = issue
            max_issue = max(max_issue, issue)
    m = re.search(r"Skipped (\d+) frames", line)
    if m:
        skipped = max(skipped, int(m.group(1)))
        if glass_on == "-":
            skipped_launch = max(skipped_launch, int(m.group(1)))
    m = re.search(r"PagelGlass: glass on at \+(\d+) ms", line)
    if m:
        glass_on = m.group(1)
# An ANR of this launch's process. A force-stopped earlier process can be reported after `logcat -c` (CI 40666139501:
# "No response to onStartJob" for the pid stopped 2 s before), so the "PID:" line under it must be this one.
anr = False
for i, l in enumerate(log):
    if "ANR in org.pagel.ai" in l:
        m = re.search(r"PID: (\d+)", " ".join(log[i + 1:i + 3]))
        anr = anr or not m or pid is None or m.group(1) == pid
# A Davey line is written only for frames over 700 ms: no line means the first frame was faster than that.
fi = f"{first_issue}" if first_issue >= 0 else "<700"
print(f"{label:<22} TotalTime={total} first-issue={fi} max-issue={max_issue} skipped-launch={skipped_launch} "
      f"skipped-all={skipped} glass-on=+{glass_on}ms "
      f"anr={'YES' if anr else 'no'}")
