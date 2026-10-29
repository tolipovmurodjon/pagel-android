"""PAGEL liquid-glass icon master, 1024 px. Writes icon-full.svg, icon-bg.svg, icon-fg.svg next to this file."""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
# The web's P (dashboard public/logos/pagel.svg, 64-unit box) and its dot.
P = "M22,48V16h12.5C41.4,16 46,20.2 46,26.4S41.4,36.8 34.5,36.8H29V48z M29,30.6h5c3,0 4.9,-1.6 4.9,-4.2S37.6,22.2 34.6,22.2H29z"
S = 11.6                      # 64-unit glyph -> px; P+dot stays inside the 66/108 safe circle (626 px)
TX, TY = 512 - 34.0 * S, 512 - 32.0 * S   # optical centre of P+dot at the tile centre
GLYPH = f'transform="translate({TX:.1f},{TY:.1f}) scale({S})"'
DOT = (44, 46, 4.3)

def defs():
    return f"""
<defs>
  <!-- background: 3 OKLCH stops lit from the top-left (rules/colour-theory: saturated darks, never black) -->
  <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#7BE8BC"/><stop offset=".48" stop-color="#14B18C"/><stop offset="1" stop-color="#05645E"/>
  </linearGradient>
  <radialGradient id="aur1" cx=".22" cy=".16" r=".62"><stop offset="0" stop-color="#E9FFF4" stop-opacity=".75"/><stop offset="1" stop-color="#E9FFF4" stop-opacity="0"/></radialGradient>
  <radialGradient id="aur2" cx=".88" cy=".86" r=".55"><stop offset="0" stop-color="#0FA3B8" stop-opacity=".55"/><stop offset="1" stop-color="#0FA3B8" stop-opacity="0"/></radialGradient>
  <radialGradient id="aur3" cx=".86" cy=".2" r=".42"><stop offset="0" stop-color="#C9F7A8" stop-opacity=".45"/><stop offset="1" stop-color="#C9F7A8" stop-opacity="0"/></radialGradient>
  <filter id="grain" x="0" y="0" width="1" height="1">
    <feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" seed="7" result="n"/>
    <feColorMatrix in="n" type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 .045 0"/>
    <feComposite in2="SourceGraphic" operator="in"/>
  </filter>
  <!-- the glyph's shape, corners softened (round joins) -->
  <g id="shape"><path {GLYPH} d="{P}" stroke="#fff" stroke-width="2.2" stroke-linejoin="round" fill="#fff" fill-rule="evenodd"/></g>
  <mask id="m"><use href="#shape"/></mask>
  <!-- glass body: frosted white, brightest top-left; the refraction tint = mint from the aurora behind it -->
  <linearGradient id="body" gradientUnits="userSpaceOnUse" x1="{TX+20*S:.0f}" y1="{TY+14*S:.0f}" x2="{TX+46*S:.0f}" y2="{TY+50*S:.0f}">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity=".92"/>
    <stop offset=".5" stop-color="#F2FFF9" stop-opacity=".74"/>
    <stop offset="1" stop-color="#C6F5E2" stop-opacity=".62"/>
  </linearGradient>
  <!-- inner rim: the glyph minus itself eroded, lit by a top-left gradient -->
  <filter id="rim" x="-5%" y="-5%" width="110%" height="110%">
    <feMorphology in="SourceAlpha" operator="erode" radius="9" result="in"/>
    <feComposite in="SourceAlpha" in2="in" operator="out" result="band"/>
    <feGaussianBlur in="band" stdDeviation="2.2" result="soft"/>
    <feComposite in="SourceGraphic" in2="soft" operator="in"/>
  </filter>
  <linearGradient id="rimLight" gradientUnits="userSpaceOnUse" x1="{TX+20*S:.0f}" y1="{TY+14*S:.0f}" x2="{TX+46*S:.0f}" y2="{TY+50*S:.0f}">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity="1"/>
    <stop offset=".45" stop-color="#FFFFFF" stop-opacity=".35"/>
    <stop offset=".8" stop-color="#DFFFF1" stop-opacity=".35"/>
    <stop offset="1" stop-color="#FFFFFF" stop-opacity=".85"/>
  </linearGradient>
  <!-- soft inner shade low-right (mint-tinted, never a dark outline) -->
  <filter id="inner" x="-10%" y="-10%" width="120%" height="120%">
    <feOffset in="SourceAlpha" dx="-14" dy="-16" result="off"/>
    <feComposite in="SourceAlpha" in2="off" operator="out" result="edge"/>
    <feGaussianBlur in="edge" stdDeviation="12" result="b"/>
    <feComposite in="b" in2="SourceAlpha" operator="in" result="c"/>
    <feFlood flood-color="#0E8F78" flood-opacity=".42"/>
    <feComposite in2="c" operator="in"/>
  </filter>
  <!-- the lift: a hue-tinted shadow under the glass -->
  <filter id="lift" x="-30%" y="-30%" width="160%" height="160%">
    <feGaussianBlur in="SourceAlpha" stdDeviation="17"/>
    <feOffset dy="18" result="s"/>
    <feFlood flood-color="#03463F" flood-opacity=".38"/>
    <feComposite in2="s" operator="in"/>
  </filter>
  <filter id="soft6"><feGaussianBlur stdDeviation="6"/></filter>
  <filter id="soft14"><feGaussianBlur stdDeviation="14"/></filter>
  <!-- the bead -->
  <radialGradient id="bead" cx=".36" cy=".3" r=".78">
    <stop offset="0" stop-color="#FFF6D2"/><stop offset=".38" stop-color="#FFD873"/><stop offset=".85" stop-color="#F3AE34"/><stop offset="1" stop-color="#E39A2A"/>
  </radialGradient>
</defs>"""

def background():
    return """
  <rect width="1024" height="1024" fill="url(#bg)"/>
  <rect width="1024" height="1024" fill="url(#aur2)"/>
  <rect width="1024" height="1024" fill="url(#aur3)"/>
  <rect width="1024" height="1024" fill="url(#aur1)"/>
  <rect width="1024" height="1024" fill="#fff" filter="url(#grain)"/>"""

def foreground():
    cx, cy, r = DOT
    bx, by, br = TX + cx * S, TY + cy * S, r * S
    return f"""
  <use href="#shape" filter="url(#lift)"/>
  <g mask="url(#m)">
    <rect width="1024" height="1024" fill="url(#body)"/>
    <!-- refraction: the aurora's light bent into the glass (a brighter top-left pool, a mint bottom) -->
    <ellipse cx="{TX+25*S:.0f}" cy="{TY+19*S:.0f}" rx="{9*S:.0f}" ry="{6*S:.0f}" fill="#fff" opacity=".55" filter="url(#soft14)"/>
    <ellipse cx="{TX+40*S:.0f}" cy="{TY+44*S:.0f}" rx="{10*S:.0f}" ry="{5*S:.0f}" fill="#7FE3C0" opacity=".35" filter="url(#soft14)"/>
    <use href="#shape" filter="url(#inner)"/>
  </g>
  <g filter="url(#rim)"><g mask="url(#m)"><rect width="1024" height="1024" fill="url(#rimLight)"/></g></g>
  <!-- a crisp specular glint on the stem's top-left corner -->
  <g mask="url(#m)"><path d="M{TX+22.8*S:.0f},{TY+30*S:.0f} V{TY+17.6*S:.0f} H{TX+30*S:.0f}" stroke="#fff" stroke-width="7" stroke-linecap="round" fill="none" opacity=".95" filter="url(#soft6)"/></g>
  <!-- bead -->
  <circle cx="{bx:.1f}" cy="{by+16:.1f}" r="{br:.1f}" fill="#7A4A00" opacity=".28" filter="url(#soft14)"/>
  <circle cx="{bx:.1f}" cy="{by:.1f}" r="{br:.1f}" fill="url(#bead)"/>
  <ellipse cx="{bx-br*.32:.1f}" cy="{by-br*.42:.1f}" rx="{br*.42:.1f}" ry="{br*.24:.1f}" fill="#fff" opacity=".9" transform="rotate(-28 {bx-br*.32:.1f} {by-br*.42:.1f})"/>
  <circle cx="{bx:.1f}" cy="{by:.1f}" r="{br-3:.1f}" fill="none" stroke="#FFF2C2" stroke-width="3" opacity=".5"/>"""

def svg(body):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">{defs()}{body}\n</svg>\n'

for name, body in [("icon-full", background() + foreground()), ("icon-bg", background()), ("icon-fg", foreground())]:
    open(os.path.join(HERE, name + ".svg"), "w", encoding="utf-8").write(svg(body))
print("ok")
