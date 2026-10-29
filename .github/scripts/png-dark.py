#!/usr/bin/env python3
"""Share of non-black pixels in a PNG (stdlib only: CI's runner may have no PIL). Usage: png-dark.py <file.png> [max].
Prints the share; exits 1 when it is above max (default 0.01). A FLAG_SECURE window comes out of screencap all black,
so the Compass walk uses this to prove a secure screen was really blocked (compass re-check N3)."""
import struct, sys, zlib

def pixels(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, w = 8, b"", 0
    while pos < len(data):
        n, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            w, h, depth, color = struct.unpack(">IIBB", body[:10])
            assert depth == 8 and color in (2, 6), f"only 8-bit RGB/RGBA (got depth {depth}, color {color})"
            bpp = 3 if color == 2 else 4
        elif kind == b"IDAT":
            idat += body
        pos += 12 + n
    raw, stride, prev, out = zlib.decompress(idat), w * bpp, bytearray(w * bpp), []
    for y in range(h):
        f, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b, c = prev[i], (prev[i - bpp] if i >= bpp else 0)
            if f == 1: line[i] = (line[i] + a) & 255
            elif f == 2: line[i] = (line[i] + b) & 255
            elif f == 3: line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        out.append(line)
        prev = line
    return w, h, bpp, out

if __name__ == "__main__":
    w, h, bpp, rows = pixels(sys.argv[1])
    limit = float(sys.argv[2]) if len(sys.argv) > 2 else 0.01
    lit = sum(1 for r in rows for x in range(w) if max(r[x * bpp:x * bpp + 3]) > 16)
    share = lit / float(w * h)
    print(f"{share:.4f} of {w}x{h} pixels are not black")
    sys.exit(1 if share > limit else 0)
