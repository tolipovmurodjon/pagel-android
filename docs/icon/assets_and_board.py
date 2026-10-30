"""Slices the 1024 masters into Android adaptive layers and draws the proof board (512 + 48 px).
usage: python assets_and_board.py <render dir> <android res dir> <board.png> <old glass 512.png>
The render dir holds icon-bg-1024.png, fg-white/-black.png and sfg-white/-black.png (the small master on white and on
black; the foreground's alpha is recovered from the pair)."""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

R, res, board_out, old_glass = sys.argv[1:5]


def unblend(white, black):
    w = np.asarray(Image.open(os.path.join(R, white)).convert('RGB')).astype(float)
    b = np.asarray(Image.open(os.path.join(R, black)).convert('RGB')).astype(float)
    a = np.clip(1 - (w - b).mean(axis=2) / 255.0, 0, 1)
    c = np.where(a[..., None] > 1e-3, b / np.maximum(a[..., None], 1e-3), 0)
    return Image.fromarray(np.dstack([np.clip(c, 0, 255), a * 255]).astype(np.uint8), 'RGBA')


bg = Image.open(os.path.join(R, 'icon-bg-1024.png')).convert('RGBA')
fg, sfg = unblend('fg-white.png', 'fg-black.png'), unblend('sfg-white.png', 'sfg-black.png')
full, small = Image.alpha_composite(bg, fg), Image.alpha_composite(bg, sfg)
full.save(os.path.join(R, 'pagel-icon-1024.png'))

# 108 dp layers. The launcher draws the icon from the density bucket, not by its size: mdpi/hdpi phones draw it
# at 48-72 px, so they get the small master; xhdpi and up get the full one.
for dpi, px, layer in [("mdpi", 108, sfg), ("hdpi", 162, sfg), ("xhdpi", 216, fg), ("xxhdpi", 324, fg), ("xxxhdpi", 432, fg)]:
    d = os.path.join(res, f"mipmap-{dpi}")
    os.makedirs(d, exist_ok=True)
    layer.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.webp"), "WEBP", lossless=True, method=6)
    bg.convert("RGB").resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_background.webp"), "WEBP", quality=92, method=6)


def flat_p(n=1024):
    """Round D's flat P, from its vector numbers."""
    s = n / 108
    yy, xx = np.mgrid[0:n, 0:n] / s
    t = np.clip(((xx - 18) * 78 + (yy - 12) * 88) / (78 ** 2 + 88 ** 2), 0, 1)[..., None]
    c0, c1, c2 = (np.array(c, float) for c in [(0x65, 0xDF, 0xAF), (0x00, 0xAD, 0x88), (0x00, 0x6C, 0x63)])
    col = np.where(t < .5, c0 + (c1 - c0) * (t / .5), c1 + (c2 - c1) * ((t - .5) / .5))
    g = np.clip(1 - np.hypot(xx - 26, yy - 20) / 58, 0, 1)[..., None] * (0x59 / 255)
    col = col * (1 - g) + 255 * g
    im = Image.fromarray(col.astype(np.uint8), "RGB").convert("RGBA")
    d = ImageDraw.Draw(im)
    o = lambda x, y: ((x * 1.25 + 9.5) * s, (y * 1.25 + 14) * s)
    d.polygon([o(22, 48), o(22, 16), o(34.5, 16), o(41, 18), o(45, 22), o(46, 26.4), o(45, 31), o(41, 35), o(34.5, 36.8), o(29, 36.8), o(29, 48)], fill=(255, 255, 255, 255))
    d.polygon([o(29, 30.6), o(34, 30.6), o(37.5, 29.6), o(38.9, 26.4), o(37.5, 23.2), o(34.6, 22.2), o(29, 22.2)], fill=tuple(col[int(26 * s), int(54 * s)].astype(int)) + (255,))
    cx, cy = o(44, 46); r = 5 * s
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(0xFF, 0xD1, 0x66, 255))
    return im


def visible(im):
    """What a launcher shows: the centre 72/108 of a full adaptive layer (every picture here is a full layer)."""
    n = im.size[0]
    a, b = int(n * 18 / 108), int(n * 90 / 108)
    return im.crop((a, a, b, b))


def masked(im, n, shape):
    t = visible(im).resize((n * 4, n * 4), Image.LANCZOS)
    m = Image.new("L", (n * 4, n * 4), 0)
    md = ImageDraw.Draw(m)
    if shape == "round":
        md.ellipse([0, 0, n * 4 - 1, n * 4 - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, n * 4 - 1, n * 4 - 1], radius=int(n * 4 * .3), fill=255)
    t.putalpha(m)
    return t.resize((n, n), Image.LANCZOS)


def lum(rgb):
    c = rgb / 255.0
    c = np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return 0.2126 * c[..., 0] + 0.7152 * c[..., 1] + 0.0722 * c[..., 2]


def glyph_contrast(pic, glyph_alpha, n):
    """WCAG ratio of the glyph's mean luminance to the tile's next to it (a 6 % ring round the glyph), at n px."""
    p = np.asarray(visible(pic).convert('RGB').resize((n, n), Image.LANCZOS)).astype(float)
    a = np.asarray(visible(glyph_alpha).resize((n, n), Image.LANCZOS)).astype(float) / 255
    inside = a > 0.85
    from PIL import ImageFilter
    grow = np.asarray(Image.fromarray((a > 0.1).astype(np.uint8) * 255).filter(ImageFilter.MaxFilter(2 * max(1, n // 32) + 1))) > 0
    ring = grow & (a < 0.1)
    li, lo = lum(p[inside]).mean(), lum(p[ring]).mean()
    return (max(li, lo) + 0.05) / (min(li, lo) + 0.05)


def p_only(im):
    """The glyph's own alpha without the bead (the bead is not the letter)."""
    a = np.asarray(im.split()[-1]).copy()
    a[int(1024 * 0.58):, int(1024 * 0.55):] = 0
    return Image.fromarray(a)


# The flat P's glyph alpha: white pixels of its own picture.
flat = flat_p()
fa = np.asarray(flat.convert('RGB')).astype(int)
flat_alpha = Image.fromarray(((fa.min(axis=2) > 245) * 255).astype(np.uint8))
flat_alpha = p_only(Image.merge('RGBA', [flat_alpha] * 4))
old = Image.open(old_glass).convert('RGBA')
# The v1 glass P (512 full tile): its glyph = the pale pixels above the tile's own lightness there.
oa = np.asarray(old.convert('RGB')).astype(float)
old_alpha = Image.fromarray(((lum(oa) > 0.62) * 255).astype(np.uint8)).resize((512, 512))
oa_cut = np.asarray(old_alpha).copy(); oa_cut[int(512 * 0.58):, int(512 * 0.55):] = 0; old_alpha = Image.fromarray(oa_cut)

scores = {
    "round D flat P": (glyph_contrast(flat, flat_alpha, 512), glyph_contrast(flat, flat_alpha, 48)),
    "glass v1": (glyph_contrast(old, old_alpha, 512), glyph_contrast(old, old_alpha, 48)),
    "glass v2": (glyph_contrast(full, p_only(fg), 512), glyph_contrast(small, p_only(sfg), 48)),
}
for k, (a, b) in scores.items():
    print(f"{k:16s} glyph/tile contrast  512 px {a:.2f}:1   48 px {b:.2f}:1")

icons = [("round D flat P", flat, flat), ("glass v1 (0.13.0)", old, old), ("glass v2 (this round)", full, small)]
W, H = 3600, 1660
board = Image.new("RGB", (W, H), (0xF2, 0xF4, 0xF8))
d = ImageDraw.Draw(board)


def font(sz, bold=False):
    for f in (["C:/Windows/Fonts/segoeuib.ttf"] if bold else []) + ["C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"]:
        if os.path.exists(f):
            return ImageFont.truetype(f, sz)
    return ImageFont.load_default()


d.text((60, 30), "PAGEL app icon · liquid glass v2 (round D fix, checker 2026-10-29)", fill=(0x10, 0x18, 0x28), font=font(48, True))
d.text((60, 96), "512 px and a true 48 px copy (the small master), squircle + round, light and dark wallpaper · glyph/tile contrast under each",
       fill=(0x55, 0x60, 0x70), font=font(26))
y = 150
for c0, c1, wname in [((0xF4, 0xF1, 0xEA), (0xDD, 0xE7, 0xF0), "light wallpaper"), ((0x1B, 0x23, 0x33), (0x0B, 0x0F, 0x18), "dark wallpaper")]:
    band = Image.new("RGB", (W - 120, 740), c0)
    bd = ImageDraw.Draw(band)
    for i in range(740):
        k = i / 740
        bd.line([(0, i), (W, i)], fill=tuple(int(c0[j] * (1 - k) + c1[j] * k) for j in range(3)))
    tx = (0xE8, 0xEC, 0xF2) if c0[0] < 100 else (0x33, 0x3D, 0x4D)
    x = 30
    for (name, big_pic, small_pic), key in zip(icons, scores):
        band.paste(masked(big_pic, 512, "squircle"), (x, 30), masked(big_pic, 512, "squircle"))
        band.paste(masked(big_pic, 512, "round"), (x + 530, 30), masked(big_pic, 512, "round"))
        sm = masked(small_pic, 48, "squircle"); band.paste(sm, (x + 1062, 60), sm)
        sm = masked(small_pic, 48, "round"); band.paste(sm, (x + 1062, 130), sm)
        bd.text((x, 560), name, fill=tx, font=font(30, True))
        a, b = scores[key]
        bd.text((x, 600), f"glyph/tile  {a:.2f}:1 at 512 · {b:.2f}:1 at 48", fill=tx, font=font(24))
        x += 1150
    bd.text((30, 700), wname, fill=(0x88, 0x93, 0xA3), font=font(22))
    board.paste(band, (60, y))
    y += 760
board.save(board_out)
full.resize((512, 512), Image.LANCZOS).save(os.path.join(os.path.dirname(board_out), "..", "icon", "pagel-icon-512.png"))
small.resize((512, 512), Image.LANCZOS).save(os.path.join(R, "pagel-icon-small-512.png"))
print("ok", board_out)
