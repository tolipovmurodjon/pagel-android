"""Fails (exit 1) when the "Back to today" pill overlaps any other text on screen (Boss ledger 4: it must never cover a card)."""
import re
import sys
import xml.etree.ElementTree as ET

def box(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(int(v) for v in m.groups()) if m else None

nodes = [(n, box(n)) for n in ET.parse(sys.argv[1]).getroot().iter("node")]
pill = next((b for n, b in nodes if n.get("content-desc") == "Back to today" and b), None)
if pill is None:
    sys.exit(0)  # no pill on this screen: nothing to keep clear
inside = lambda b: b[0] >= pill[0] and b[1] >= pill[1] and b[2] <= pill[2] and b[3] <= pill[3]
hits = [n.get("text") for n, b in nodes
        if b and n.get("text") and not inside(b) and b[0] < pill[2] and b[2] > pill[0] and b[1] < pill[3] and b[3] > pill[1]]
if hits:
    print("pill overlaps:", hits)
    sys.exit(1)
