"""PAGEL liquid-glass icon masters, 1024 px. Writes icon-full/-bg/-fg.svg and, for the small sizes (the 48 px master,
used by the mdpi/hdpi buckets), icon-small-full/-fg.svg next to this file.
usage: python make_svg.py [DX DY]   (DX, DY = optical centring nudges in px, measured by assets_and_board.py)

v2 (round D checker 2026-10-29, "frosted resin, not glass"): the light pool sits in the tile's corner; the tile under
the glyph is mid teal; the body is clear (a 1.06x refracted copy of the tile shows through, 50-30 % white over it);
the edge light is a crisp 2 px rim, white .95 top-left to .25 bottom-right, plus a mint caustic low-right; no dark inner
shade and no dark lift shadow (brain rules/icon-rebuild-1to1 rule 12: light on edges, never a dark outline or shade)."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
# The web's P (dashboard public/logos/pagel.svg, 64-unit box) and its dot.
P = "M22,48V16h12.5C41.4,16 46,20.2 46,26.4S41.4,36.8 34.5,36.8H29V48z M29,30.6h5c3,0 4.9,-1.6 4.9,-4.2S37.6,22.2 34.6,22.2H29z"
S = 11.6                      # 64-unit glyph -> px; P+dot stays inside the 66/108 safe circle (626 px)
DX, DY = (float(sys.argv[1]), float(sys.argv[2])) if len(sys.argv) > 2 else (0.0, 0.0)
TX, TY = 512 - 34.0 * S + DX, 512 - 32.0 * S + DY
GLYPH = f'transform="translate({TX:.1f},{TY:.1f}) scale({S})"'
DOT = (44, 46, 4.3)


def defs(small: bool) -> str:
    soft = 3.3 if small else 2.2               # the small master's stem is ~8 % heavier
    rim_px = 12 if small else 2                # ~1 px at 48 px on screen, 2 px at 512
    body = (".90", ".82", ".74") if small else (".50", ".38", ".30")
    return f"""
<defs>
  <!-- background: 3 OKLCH stops lit from the top-left; mid teal (L ~0.60) under the glyph -->
  <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#6FE0B6"/><stop offset=".5" stop-color="#109A80"/><stop offset="1" stop-color="#05645E"/>
  </linearGradient>
  <!-- the light pool in the tile's corner (it used to sit behind the stem: 1.50:1 there) -->
  <radialGradient id="aur1" cx=".08" cy=".06" r=".35"><stop offset="0" stop-color="#E9FFF4" stop-opacity=".85"/><stop offset="1" stop-color="#E9FFF4" stop-opacity="0"/></radialGradient>
  <radialGradient id="aur2" cx=".9" cy=".88" r=".5"><stop offset="0" stop-color="#0FA3B8" stop-opacity=".5"/><stop offset="1" stop-color="#0FA3B8" stop-opacity="0"/></radialGradient>
  <radialGradient id="aur3" cx=".9" cy=".16" r=".38"><stop offset="0" stop-color="#C9F7A8" stop-opacity=".4"/><stop offset="1" stop-color="#C9F7A8" stop-opacity="0"/></radialGradient>
  <filter id="grain" x="0" y="0" width="1" height="1">
    <feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" seed="7" result="n"/>
    <feColorMatrix in="n" type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 .04 0"/>
    <feComposite in2="SourceGraphic" operator="in"/>
  </filter>
  <g id="tile">
    <rect width="1024" height="1024" fill="url(#bg)"/>
    <rect width="1024" height="1024" fill="url(#aur2)"/>
    <rect width="1024" height="1024" fill="url(#aur3)"/>
    <rect width="1024" height="1024" fill="url(#aur1)"/>
  </g>
  <!-- the glyph's shape, corners softened (round joins) -->
  <g id="shape"><path {GLYPH} d="{P}" stroke="#fff" stroke-width="{soft}" stroke-linejoin="round" fill="#fff" fill-rule="evenodd"/></g>
  <mask id="m"><use href="#shape"/></mask>
  <!-- refraction: the tile seen through the glass, magnified 1.06x, its edges bent a few px, a touch brighter -->
  <filter id="refr" x="-5%" y="-5%" width="110%" height="110%">
    <feTurbulence type="fractalNoise" baseFrequency=".011" numOctaves="1" seed="3" result="w"/>
    <feDisplacementMap in="SourceGraphic" in2="w" scale="{2 if small else 7}" xChannelSelector="R" yChannelSelector="G" result="d"/>
    <feComponentTransfer in="d"><feFuncR type="linear" slope="1.14" intercept=".03"/><feFuncG type="linear" slope="1.14" intercept=".03"/><feFuncB type="linear" slope="1.14" intercept=".03"/></feComponentTransfer>
  </filter>
  <!-- the clear body: white over the refracted tile, strongest top-left -->
  <linearGradient id="body" gradientUnits="userSpaceOnUse" x1="{TX+20*S:.0f}" y1="{TY+14*S:.0f}" x2="{TX+46*S:.0f}" y2="{TY+50*S:.0f}">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity="{body[0]}"/>
    <stop offset=".5" stop-color="#F4FFFA" stop-opacity="{body[1]}"/>
    <stop offset="1" stop-color="#DDFBEF" stop-opacity="{body[2]}"/>
  </linearGradient>
  <!-- the crisp rim: one light from the top-left, never a dark edge -->
  <linearGradient id="rimLight" gradientUnits="userSpaceOnUse" x1="{TX+20*S:.0f}" y1="{TY+14*S:.0f}" x2="{TX+46*S:.0f}" y2="{TY+50*S:.0f}">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity=".95"/>
    <stop offset=".55" stop-color="#FFFFFF" stop-opacity=".5"/>
    <stop offset="1" stop-color="#FFFFFF" stop-opacity=".25"/>
  </linearGradient>
  <!-- the rim band: the glyph minus itself eroded by the rim width, no blur (crisp) -->
  <filter id="rim" x="-5%" y="-5%" width="110%" height="110%">
    <feMorphology in="SourceAlpha" operator="erode" radius="{rim_px}" result="in"/>
    <feComposite in="SourceAlpha" in2="in" operator="out" result="band"/>
    <feComposite in="SourceGraphic" in2="band" operator="in"/>
  </filter>
  <filter id="soft3"><feGaussianBlur stdDeviation="7"/></filter>
  <filter id="soft1"><feGaussianBlur stdDeviation="1.2"/></filter>
  <radialGradient id="bead" cx=".36" cy=".3" r=".78">
    <stop offset="0" stop-color="#FFF6D2"/><stop offset=".38" stop-color="#FFD873"/><stop offset=".85" stop-color="#F3AE34"/><stop offset="1" stop-color="#E39A2A"/>
  </radialGradient>
</defs>""", soft, rim_px


def background() -> str:
    return """
  <use href="#tile"/>
  <rect width="1024" height="1024" fill="#fff" filter="url(#grain)"/>"""


def foreground(soft: float, rim_px: float, small: bool) -> str:
    cx, cy, r = DOT
    bead = 1.10 if small else 1.0
    bx, by, br = TX + cx * S, TY + cy * S, r * S * bead
    gx, gy = TX + 34 * S, TY + 32 * S
    glint = ""  # the rim carries the top-left light; a separate glint floated inside the glyph like a bracket

    caustic = f"""<ellipse cx="{TX+40.5*S:.0f}" cy="{TY+34.6*S:.0f}" rx="{3.2*S:.0f}" ry="{1.1*S:.0f}" fill="#C8FFE9" opacity="{'.35' if small else '.55'}" transform="rotate(-24 {TX+40.5*S:.0f} {TY+34.6*S:.0f})" {'' if small else 'filter="url(#soft3)"'}/>
    <ellipse cx="{TX+26.5*S:.0f}" cy="{TY+46.4*S:.0f}" rx="{2.6*S:.0f}" ry="{0.8*S:.0f}" fill="#C8FFE9" opacity="{'.3' if small else '.45'}" {'' if small else 'filter="url(#soft3)"'}/>"""
    return f"""
  <g mask="url(#m)">
    <g transform="translate({gx:.1f},{gy:.1f}) scale(1.06) translate({-gx:.1f},{-gy:.1f})" filter="url(#refr)"><use href="#tile"/></g>
    <rect width="1024" height="1024" fill="url(#body)"/>
    {caustic}
  </g>
  <g filter="url(#rim)"><g mask="url(#m)"><rect width="1024" height="1024" fill="url(#rimLight)"/></g></g>{glint}
  <!-- bead -->
  <circle cx="{bx:.1f}" cy="{by:.1f}" r="{br:.1f}" fill="url(#bead)"/>
  <ellipse cx="{bx-br*.32:.1f}" cy="{by-br*.42:.1f}" rx="{br*.42:.1f}" ry="{br*.24:.1f}" fill="#fff" opacity=".9" transform="rotate(-28 {bx-br*.32:.1f} {by-br*.42:.1f})"/>
  <circle cx="{bx:.1f}" cy="{by:.1f}" r="{br-3:.1f}" fill="none" stroke="#FFF2C2" stroke-width="3" opacity=".5"/>"""


def svg(d: str, body: str) -> str:
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">{d}{body}\n</svg>\n'


for small in (False, True):
    d, soft, rim = defs(small)
    fg = foreground(soft, rim, small)
    pre = "icon-small" if small else "icon"
    files = [(pre + "-full", background() + fg), (pre + "-fg", fg)] + ([] if small else [("icon-bg", background())])
    for name, body in files:
        open(os.path.join(HERE, name + ".svg"), "w", encoding="utf-8").write(svg(d, body))
print("ok", DX, DY)
