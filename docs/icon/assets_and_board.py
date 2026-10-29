"""Slices the 1024 master into Android adaptive layers and draws the proof board.
usage: python assets_and_board.py <android res dir> <board.png>"""
import os, sys, subprocess
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
res, board_out = sys.argv[1], sys.argv[2]
fg = Image.open(os.path.join(HERE, "icon-fg-1024.png")).convert("RGBA")
bg = Image.open(os.path.join(HERE, "icon-bg-1024.png")).convert("RGBA")
full = Image.alpha_composite(bg, fg)

# 108 dp layers: mdpi 108 px ... xxxhdpi 432 px.
for dpi, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]:
    d = os.path.join(res, f"mipmap-{dpi}")
    os.makedirs(d, exist_ok=True)
    fg.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.webp"), "WEBP", lossless=True, method=6)
    bg.convert("RGB").resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_background.webp"), "WEBP", quality=92, method=6)

# Round D's flat P (the vector this replaces), rendered from its own numbers.
def flat_p(n):
    s = n / 108
    yy, xx = np.mgrid[0:n, 0:n] / s
    t = np.clip(((xx - 18) * 78 + (yy - 12) * 88) / (78 ** 2 + 88 ** 2), 0, 1)[..., None]
    c0, c1, c2 = (np.array(c, float) for c in [(0x65, 0xDF, 0xAF), (0x00, 0xAD, 0x88), (0x00, 0x6C, 0x63)])
    col = np.where(t < .5, c0 + (c1 - c0) * (t / .5), c1 + (c2 - c1) * ((t - .5) / .5))
    g = np.clip(1 - np.hypot(xx - 26, yy - 20) / 58, 0, 1)[..., None] * (0x59 / 255)
    col = col * (1 - g) + 255 * g
    im = Image.fromarray(col.astype(np.uint8), "RGB").convert("RGBA")
    d = ImageDraw.Draw(im)
    def P(dx, dy, fill):
        k = 1.25 * s
        o = lambda x, y: ((x * 1.25 + dx) * s, (y * 1.25 + dy) * s)
        d.polygon([o(22, 48), o(22, 16), o(34.5, 16), o(41, 18), o(45, 22), o(46, 26.4), o(45, 31), o(41, 35), o(34.5, 36.8), o(29, 36.8), o(29, 48)], fill=fill)
        if fill[3] == 255:
            d.polygon([o(29, 30.6), o(34, 30.6), o(37.5, 29.6), o(38.9, 26.4), o(37.5, 23.2), o(34.6, 22.2), o(29, 22.2)], fill=tuple(col[int(26 * s), int(54 * s)].astype(int)) + (255,))
        return o
    P(9.5, 15.5, (0, 70, 67, 64)); o = P(9.5, 14, (255, 255, 255, 255))
    cx, cy = o(44, 46); r = 4 * 1.25 * s
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(0xFF, 0xD1, 0x66, 255))
    return im

# The default Android robot this app shipped with before round D (a plain stand-in: grey-green head, two eyes).
def robot(n):
    im = Image.new("RGBA", (n, n), (255, 255, 255, 255))
    d = ImageDraw.Draw(im)
    g = (0x3D, 0xDC, 0x84, 255)
    d.pieslice([n * .2, n * .3, n * .8, n * .9], 180, 360, fill=g)
    for x in (.38, .62):
        d.ellipse([n * (x - .04), n * .47, n * (x + .04), n * .55], fill=(255, 255, 255, 255))
    d.line([n * .3, n * .28, n * .36, n * .38], fill=g, width=max(1, n // 40)); d.line([n * .7, n * .28, n * .64, n * .38], fill=g, width=max(1, n // 40))
    return im

def masked(im, n, shape):
    # A launcher shows the centre 72/108 of the layers, scaled to the icon size.
    crop = im.crop((int(1024 * 18 / 108), int(1024 * 18 / 108), int(1024 * 90 / 108), int(1024 * 90 / 108))) if im.size[0] == 1024 else im
    t = crop.resize((n * 4, n * 4), Image.LANCZOS)
    m = Image.new("L", (n * 4, n * 4), 0)
    md = ImageDraw.Draw(m)
    if shape == "round": md.ellipse([0, 0, n * 4 - 1, n * 4 - 1], fill=255)
    else: md.rounded_rectangle([0, 0, n * 4 - 1, n * 4 - 1], radius=int(n * 4 * .3), fill=255)
    t.putalpha(m)
    return t.resize((n, n), Image.LANCZOS)

flat = flat_p(1024)
crop_old = lambda im: im.crop((int(1024 * 18 / 108),) * 2 + (int(1024 * 90 / 108),) * 2)
W, H = 2200, 1500
board = Image.new("RGB", (W, H), (0xF2, 0xF4, 0xF8))
d = ImageDraw.Draw(board)
def font(sz, bold=False):
    for f in (["C:/Windows/Fonts/segoeuib.ttf"] if bold else []) + ["C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"]:
        if os.path.exists(f): return ImageFont.truetype(f, sz)
    return ImageFont.load_default()
d.text((60, 40), "PAGEL app icon · real liquid glass (round D, Boss 2026-10-29)", fill=(0x10, 0x18, 0x28), font=font(44, True))
d.text((60, 104), "512 px and a true 48 px copy, round + squircle masks, light and dark wallpapers · left to right: Android robot (before round D) · round D flat P · NEW glass P",
       fill=(0x55, 0x60, 0x70), font=font(24))
icons = [("robot (old)", robot(1024)), ("round D flat P", flat), ("NEW glass P", full)]
walls = [((0xF4, 0xF1, 0xEA), (0xDD, 0xE7, 0xF0), "light wallpaper"), ((0x1B, 0x23, 0x33), (0x0B, 0x0F, 0x18), "dark wallpaper")]
y = 170
for c0, c1, wname in walls:
    band = Image.new("RGB", (W - 120, 620), c0)
    bd = ImageDraw.Draw(band)
    for i in range(620):
        k = i / 620; bd.line([(0, i), (W, i)], fill=tuple(int(c0[j] * (1 - k) + c1[j] * k) for j in range(3)))
    x = 40
    for name, im in icons:
        for shape in ("squircle", "round"):
            big = masked(im if im.size[0] == 1024 else im, 240, shape)
            band.paste(big, (x, 60), big)
            small = masked(im, 48, shape)
            band.paste(small, (x + 96, 330), small)
            tx = (0xE8, 0xEC, 0xF2) if c0[0] < 100 else (0x33, 0x3D, 0x4D)
            bd.text((x, 400), f"{name}", fill=tx, font=font(22, True))
            bd.text((x, 430), f"{shape} · 240 + 48 px", fill=tx, font=font(20))
            x += 335
    bd.text((40, 560), wname, fill=(0x88, 0x93, 0xA3), font=font(20))
    board.paste(band, (60, y))
    y += 650
board.save(board_out)
full.save(os.path.join(HERE, "pagel-icon-1024.png"))
full.resize((512, 512), Image.LANCZOS).save(os.path.join(HERE, "pagel-icon-512.png"))
print("ok", board_out)
