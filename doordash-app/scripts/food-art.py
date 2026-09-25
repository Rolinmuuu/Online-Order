"""Generates the menu and restaurant illustrations in public/food/ (flat vector style, 4:3).

Run: python3 scripts/food-art.py   — no dependencies. All artwork is original and drawn from
simple shapes, so the demo has no third-party photos or brand imagery.
"""
import math, os, random

OUT = os.path.join(os.path.dirname(__file__), "..", "public", "food")
W, H = 800, 600

def svg(name, bg, body, extra_defs=""):
    doc = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
<defs>
  <radialGradient id="glow" cx="50%" cy="45%" r="65%"><stop offset="0" stop-color="#fff" stop-opacity=".55"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
  <filter id="soft" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="14"/></filter>
  {extra_defs}
</defs>
<rect width="{W}" height="{H}" fill="{bg}"/>
<rect width="{W}" height="{H}" fill="url(#glow)"/>
{body}
</svg>'''
    with open(os.path.join(OUT, name + ".svg"), "w") as f:
        f.write(doc)

def shadow(cx, cy, rx, ry, op=.18):
    return f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="#3b2a1a" opacity="{op}" filter="url(#soft)"/>'

def sesame(cx, cy, rx, ry, n=14, seed=1):
    r = random.Random(seed); s = ""
    for _ in range(n):
        a = r.uniform(math.pi*1.08, math.pi*1.92); d = r.uniform(.2, .85)
        x = cx + math.cos(a)*rx*d; y = cy + math.sin(a)*ry*d
        s += f'<ellipse cx="{x:.0f}" cy="{y:.0f}" rx="7" ry="4" fill="#fff6dc" transform="rotate({r.randint(-40,40)} {x:.0f} {y:.0f})"/>'
    return s

def burger(dark=False, extra=""):
    bun = "#8a4b1f" if dark else "#e39a3b"; bun_hi = "#a6602b" if dark else "#f0b25a"
    return (shadow(400, 470, 250, 34) +
        f'<path d="M170 400 q230 60 460 0 l-10 34 q-220 52 -440 0z" fill="{bun}"/>'  # bottom bun
        '<path d="M175 382 q225 40 450 0 q8 20 -6 28 q-219 40 -438 0 q-14 -8 -6 -28z" fill="#5a2e17"/>'  # patty
        '<path d="M185 356 q215 36 430 0 q10 18 -4 26 q-211 36 -422 0 q-14 -8 -4 -26z" fill="#6b3a1e"/>'
        '<path d="M180 350 l440 0 l-30 38 l-40 -22 l-40 30 l-50 -26 l-45 32 l-45 -30 l-50 28 l-45 -30 l-40 26z" fill="#f7c948"/>'  # cheese
        '<path d="M165 338 q30 -18 60 0 q30 18 60 0 q30 -18 60 0 q30 18 60 0 q30 -18 60 0 q30 18 60 0 q30 -18 60 0 l0 16 l-420 0z" fill="#6aa84f"/>'
        f'<path d="M175 336 q0 -150 225 -150 q225 0 225 150z" fill="{bun}"/>'
        f'<path d="M215 300 q20 -90 150 -100" stroke="{bun_hi}" stroke-width="22" fill="none" stroke-linecap="round" opacity=".7"/>'
        + sesame(400, 330, 200, 130, seed=3 if dark else 7) + extra)

def fries():
    s = shadow(400, 500, 170, 26)
    r = random.Random(4)
    for i in range(11):
        x = 285 + i*23 + r.randint(-6, 6); top = 150 + r.randint(0, 60); tilt = r.randint(-8, 8)
        s += f'<rect x="{x}" y="{top}" width="26" height="230" rx="6" fill="#f3c24e" stroke="#d99a2b" stroke-width="3" transform="rotate({tilt} {x+13} 380)"/>'
    s += '<path d="M265 300 l270 0 l-30 205 l-210 0z" fill="#d6453d"/><path d="M265 300 l270 0 l-6 40 l-258 0z" fill="#b8352f"/>'
    s += '<circle cx="400" cy="415" r="44" fill="#fff4e0"/><path d="M380 415 q20 -34 40 0 q-20 34 -40 0" fill="#d6453d"/>'
    return s

def shake():
    return (shadow(400, 520, 130, 24) +
        '<path d="M300 230 l200 0 l-28 290 l-144 0z" fill="#fdf7ef" stroke="#e6d6c2" stroke-width="6"/>'
        '<path d="M306 290 l188 0 l-22 226 l-144 0z" fill="#f2e1c9"/>'
        '<path d="M290 230 q20 -60 60 -40 q20 -50 60 -20 q40 -40 70 10 q40 0 30 50z" fill="#fffaf2" stroke="#eadcc8" stroke-width="5"/>'
        '<circle cx="418" cy="150" r="24" fill="#c0262d"/><path d="M420 128 q10 -40 40 -52" stroke="#5c3b1e" stroke-width="5" fill="none"/>'
        '<rect x="455" y="90" width="16" height="190" rx="8" fill="#f28b82" transform="rotate(14 463 185)"/>'
        '<rect x="315" y="330" width="170" height="14" rx="7" fill="#e7c9a2" opacity=".6"/>')

def pot(broth, ingredients, lip="#2b2b2b"):
    return (shadow(400, 505, 240, 36) +
        f'<ellipse cx="400" cy="330" rx="235" ry="150" fill="#222"/>'
        f'<ellipse cx="400" cy="318" rx="232" ry="140" fill="{lip}"/>'
        f'<ellipse cx="400" cy="318" rx="200" ry="118" fill="{broth}"/>'
        f'<ellipse cx="360" cy="290" rx="120" ry="50" fill="#fff" opacity=".12"/>'
        + ingredients +
        '<rect x="120" y="300" width="40" height="22" rx="10" fill="#1a1a1a"/><rect x="640" y="300" width="40" height="22" rx="10" fill="#1a1a1a"/>')

def tofu_cubes(pos):
    return "".join(f'<rect x="{x}" y="{y}" width="58" height="46" rx="10" fill="#fbf6e9" stroke="#eadfc6" stroke-width="3" transform="rotate({a} {x+29} {y+23})"/>' for x, y, a in pos)

def scallions(n, cx, cy, spread, seed):
    r = random.Random(seed); s = ""
    for _ in range(n):
        x = cx + r.uniform(-spread, spread); y = cy + r.uniform(-spread*.5, spread*.5)
        s += f'<circle cx="{x:.0f}" cy="{y:.0f}" r="9" fill="#7cc36a" stroke="#4f9a3c" stroke-width="3"/>'
    return s

def egg(cx, cy, s=1.0):
    return (f'<ellipse cx="{cx}" cy="{cy}" rx="{46*s}" ry="{36*s}" fill="#fffdf6"/>'
            f'<circle cx="{cx+4*s}" cy="{cy-2*s}" r="{18*s}" fill="#f6b23c"/>')

def tofu_stew():
    ing = tofu_cubes([(300, 270, -8), (430, 300, 10), (360, 340, 4)])
    ing += '<path d="M470 250 q40 -20 70 10 q-30 30 -70 10z" fill="#f0a3a0"/><path d="M250 330 q30 -30 60 0 q-30 25 -60 0z" fill="#f0a3a0"/>'
    ing += '<path d="M330 230 q30 -12 50 8 l-6 12 q-24 -12 -40 -4z" fill="#c43b2c" opacity=".8"/>'
    return pot("#d8432f", ing + egg(470, 360, .9) + scallions(8, 400, 300, 140, 11))

def seafood_stew():
    ing = tofu_cubes([(290, 300, 6), (420, 330, -6)])
    ing += '<path d="M450 250 q40 -40 80 0 q-10 30 -40 30 q-30 -5 -40 -30z" fill="#ff9d6b" stroke="#e46b3a" stroke-width="4"/>'
    ing += '<path d="M260 250 q30 -40 70 -10 q-5 30 -35 32 q-25 -2 -35 -22z" fill="#ff9d6b" stroke="#e46b3a" stroke-width="4"/>'
    ing += '<ellipse cx="360" cy="260" rx="34" ry="24" fill="#6d6f7a"/><ellipse cx="360" cy="258" rx="26" ry="16" fill="#f3e3cf"/>'
    ing += '<ellipse cx="520" cy="350" rx="34" ry="24" fill="#6d6f7a"/><ellipse cx="520" cy="348" rx="26" ry="16" fill="#f3e3cf"/>'
    return pot("#ef8a3a", ing + scallions(7, 400, 310, 150, 5))

def plate(inner, rim="#fbf7f0", dish="#f1e8da"):
    return (shadow(400, 470, 280, 40) +
            f'<ellipse cx="400" cy="330" rx="300" ry="190" fill="{rim}" stroke="#e5d8c5" stroke-width="4"/>'
            f'<ellipse cx="400" cy="330" rx="235" ry="140" fill="{dish}"/>' + inner)

def pancake():
    s = '<circle cx="400" cy="325" r="190" fill="#e8b25a" transform="scale(1 .72) translate(0 126)"/>'
    s = '<ellipse cx="400" cy="325" rx="200" ry="135" fill="#e6ad55"/><ellipse cx="400" cy="318" rx="185" ry="122" fill="#f0c170"/>'
    r = random.Random(9)
    for _ in range(18):
        x = r.randint(250, 550); y = r.randint(240, 400); a = r.randint(0, 180)
        s += f'<rect x="{x}" y="{y}" width="70" height="12" rx="6" fill="#5fa84a" transform="rotate({a} {x+35} {y+6})"/>'
    for _ in range(6):
        x = r.randint(280, 520); y = r.randint(260, 380)
        s += f'<path d="M{x} {y} q20 -24 44 0 q-8 18 -22 18 q-16 0 -22 -18z" fill="#ffa37a" stroke="#e46b3a" stroke-width="3"/>'
    s += '<path d="M400 205 L400 445 M260 325 L540 325 M300 240 L500 410 M300 410 L500 240" stroke="#c98a36" stroke-width="3" opacity=".5"/>'
    s += '<ellipse cx="610" cy="430" rx="50" ry="26" fill="#3b2415"/><ellipse cx="610" cy="424" rx="42" ry="18" fill="#5a3620"/>'
    return plate(s, dish="#f4ecdf")

def short_ribs():
    s = '<g transform="translate(-200 -150) scale(1.5)">'
    for i, (x, y) in enumerate([(300, 280), (380, 300), (460, 280), (340, 350), (430, 360)]):
        s += f'<rect x="{x}" y="{y}" width="90" height="46" rx="18" fill="#7a3419" transform="rotate({-12+i*6} {x+45} {y+23})"/>'
        s += f'<rect x="{x+8}" y="{y+8}" width="74" height="10" rx="5" fill="#a3542a" transform="rotate({-12+i*6} {x+45} {y+23})"/>'
        s += f'<rect x="{x-14}" y="{y+16}" width="22" height="14" rx="6" fill="#f3ead8" transform="rotate({-12+i*6} {x+45} {y+23})"/>'
    s += scallions(6, 390, 320, 80, 21) + sesame_top() + '</g>'
    return plate(s)

def sesame_top():
    r = random.Random(33); s = ""
    for _ in range(18):
        x = r.randint(300, 510); y = r.randint(270, 400)
        s += f'<ellipse cx="{x}" cy="{y}" rx="5" ry="3" fill="#fff4d6"/>'
    return s

def bowl(fill_body, bowl_color="#2f5d8a", rim="#f7f3ea"):
    return (shadow(400, 505, 230, 34) +
            f'<path d="M160 300 q240 320 480 0z" fill="{bowl_color}"/>'
            f'<path d="M190 330 q40 18 80 0 q40 -18 80 0 q40 18 80 0 q40 -18 80 0 q40 18 80 0" stroke="#fff" stroke-width="6" fill="none" opacity=".35"/>'
            f'<ellipse cx="400" cy="300" rx="240" ry="70" fill="{rim}"/>'
            f'<ellipse cx="400" cy="302" rx="222" ry="58" fill="#3a1f14"/>' + fill_body)

def mapo():
    s = '<ellipse cx="400" cy="300" rx="215" ry="54" fill="#c0331f"/>'
    s += tofu_cubes([(290, 268, 0), (360, 282, 0), (440, 268, 0), (320, 300, 0), (470, 296, 0)]).replace('width="58" height="46"', 'width="46" height="30"')
    r = random.Random(2)
    for _ in range(40):
        x = r.randint(220, 580); y = r.randint(262, 336)
        s += f'<circle cx="{x}" cy="{y}" r="{r.randint(3,6)}" fill="#7a2413"/>'
    s += scallions(10, 400, 296, 150, 8).replace('r="9"', 'r="6"')
    return bowl(s, bowl_color="#e9e2d5", rim="#fbf8f2")

def dumplings():
    s = ""
    for i, (x, y) in enumerate([(300, 290), (410, 270), (520, 300), (350, 370), (470, 380)]):
        s += (f'<path d="M{x-60} {y+20} q60 -90 120 0 q-60 30 -120 0z" fill="#fbf1de" stroke="#e8d7b8" stroke-width="4"/>'
              f'<path d="M{x-40} {y-8} q10 10 20 0 q10 10 20 0 q10 10 20 0 q10 10 20 0" stroke="#e8d7b8" stroke-width="4" fill="none"/>')
    # chili oil pooled around the dumplings
    s = ('<ellipse cx="410" cy="345" rx="215" ry="105" fill="#c7361f" opacity=".35"/>'
         '<ellipse cx="410" cy="345" rx="170" ry="78" fill="#b3301b" opacity=".35"/>') + s
    r = random.Random(17)
    for _ in range(30):
        x = r.randint(230, 590); y = r.randint(270, 420)
        s += f'<circle cx="{x}" cy="{y}" r="{r.randint(2,4)}" fill="#8e2412" opacity=".8"/>'
    s += scallions(8, 410, 330, 150, 4).replace('r="9"', 'r="6"')
    return plate(s, dish="#efe4d2")

def dan_dan():
    s = '<ellipse cx="400" cy="300" rx="215" ry="54" fill="#b8481f"/>'
    for i in range(14):
        y = 272 + i*4
        s += f'<path d="M{220+i*5} {y} q60 -20 120 0 q60 20 120 0 q60 -20 120 0" stroke="#f3d38d" stroke-width="7" fill="none"/>'
    s += '<circle cx="470" cy="286" r="34" fill="#6b3a1e"/>'
    r = random.Random(6)
    for _ in range(26):
        x = r.randint(300, 520); y = r.randint(270, 330)
        s += f'<ellipse cx="{x}" cy="{y}" rx="6" ry="4" fill="#d9a15a"/>'
    s += '<path d="M330 290 q20 -30 60 -20 q-10 26 -60 20z" fill="#5fa84a"/>'
    s += '<rect x="520" y="120" width="12" height="220" rx="6" fill="#c9a26b" transform="rotate(28 526 230)"/><rect x="548" y="120" width="12" height="220" rx="6" fill="#c9a26b" transform="rotate(32 554 230)"/>'
    return bowl(s)

def green_beans():
    r = random.Random(12); s = ""
    for _ in range(26):
        x = r.randint(250, 520); y = r.randint(250, 400); a = r.randint(-60, 60)
        s += f'<rect x="{x}" y="{y}" width="120" height="16" rx="8" fill="#4f8f3a" transform="rotate({a} {x+60} {y+8})"/>'
        s += f'<rect x="{x+10}" y="{y+3}" width="90" height="4" rx="2" fill="#7cbf5e" transform="rotate({a} {x+60} {y+8})"/>'
    for _ in range(16):
        x = r.randint(280, 520); y = r.randint(260, 390)
        s += f'<circle cx="{x}" cy="{y}" r="5" fill="#f2e3c0"/>'
    return plate(s, dish="#efe6d6")

def chicken_sandwich():
    return (shadow(400, 470, 240, 32) +
        '<path d="M175 400 q225 56 450 0 l-10 32 q-215 48 -430 0z" fill="#e6a34b"/>'
        '<path d="M160 360 q20 -30 60 -10 q30 -30 70 -6 q40 -30 80 -2 q40 -30 80 0 q40 -26 80 0 q30 -20 70 14 q10 30 -10 40 q-220 50 -440 0 q-12 -16 10 -36z" fill="#d4892f"/>'
        '<path d="M190 368 q210 40 420 0" stroke="#b56d23" stroke-width="8" fill="none" opacity=".6"/>'
        '<path d="M170 342 q230 30 460 0 l0 14 q-230 30 -460 0z" fill="#ffffff" opacity=".85"/>'
        '<path d="M175 336 q20 -12 40 0 q20 12 40 0 q20 -12 40 0 q20 12 40 0 q20 -12 40 0 q20 12 40 0 q20 -12 40 0 q20 12 40 0 q20 -12 40 0 q20 12 40 0 q20 -12 40 0 l0 12 l-440 0z" fill="#8cc56f"/>'
        '<path d="M178 332 q0 -140 222 -140 q222 0 222 140z" fill="#e6a34b"/>'
        '<path d="M220 296 q20 -84 150 -94" stroke="#f2bf6a" stroke-width="20" fill="none" stroke-linecap="round" opacity=".7"/>'
        + "".join(f'<ellipse cx="{x}" cy="{y}" rx="18" ry="8" fill="#9ccf7c" stroke="#6aa84f" stroke-width="3"/>' for x, y in [(260, 395), (330, 402), (470, 402), (540, 395)]))

def cover(name, bg, motif):
    svg(name, bg, motif)

os.makedirs(OUT, exist_ok=True)
svg("burger", "#fde9c9", burger())
truffle = "".join(f'<circle cx="{x}" cy="{y}" r="{r}" fill="#2a1d14" opacity=".9"/><circle cx="{x-3}" cy="{y-3}" r="{r*0.4:.0f}" fill="#5a4535"/>' for x, y, r in [(330, 250, 14), (400, 235, 16), (470, 255, 13), (365, 285, 11), (440, 290, 12)])
svg("truffle-burger", "#e9e1d6", burger(dark=True, extra=truffle))
svg("chicken-sandwich", "#fbe3d0", chicken_sandwich())
svg("fries", "#fff1c9", fries())
svg("shake", "#f9dfe6", shake())
svg("tofu-stew", "#fbe1d7", tofu_stew())
svg("seafood-stew", "#fde8d2", seafood_stew())
svg("pancake", "#e6f0dc", pancake())
svg("short-ribs", "#f3e2d2", short_ribs())
svg("mapo-tofu", "#f7d9d0", mapo())
svg("dumplings", "#f1e6d8", dumplings())
svg("dan-dan", "#fae3cc", dan_dan())
svg("green-beans", "#e3efd9", green_beans())

# restaurant covers: a pattern of the house dish on a strong colour
def tiled(inner_name_fn, bg):
    return f'<g opacity=".95">{inner_name_fn()}</g>'
cover("cover-burgers", "#f4a340", f'<g transform="translate(-40 10) scale(1.05)">{burger()}</g>')
cover("cover-tofu", "#e2583e", f'<g transform="translate(0 0)">{tofu_stew()}</g>')
cover("cover-wok", "#2f6b4f", '<path d="M150 330 q250 200 500 0z" fill="#1f1f1f"/><ellipse cx="400" cy="330" rx="250" ry="40" fill="#2d2d2d"/>'
      '<rect x="640" y="316" width="130" height="22" rx="11" fill="#6b4a2b"/>'
      + "".join(f'<path d="M{300+i*50} 300 q{-10+i*4} -{90+i%2*40} {20} -{160+i%3*30}" stroke="{c}" stroke-width="16" fill="none" stroke-linecap="round" opacity=".9"/>'
                for i, c in enumerate(["#f7c948", "#f28c28", "#e2583e", "#f7c948", "#f28c28"]))
      + '<ellipse cx="400" cy="320" rx="200" ry="30" fill="#b8481f"/>' + scallions(8, 400, 318, 150, 3).replace('r="9"', 'r="7"'))
print("wrote", len(os.listdir(OUT)), "files to", os.path.abspath(OUT))
