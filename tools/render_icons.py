#!/usr/bin/env python3
"""Renders the Emberwatch icon (same paths as res/drawable/ic_launcher_foreground.xml)
into legacy mipmap PNGs, PWA icons and an SVG. Requires Pillow. Run from repo root:
    python3 tools/render_icons.py
"""
import os, re
from PIL import Image, ImageDraw

BG = "#0B0E13"
PATHS = [  # (fill, path) in a 108x108 adaptive-icon viewport
    ("#4A6F8A", "M42,82 L66,82 L62,50 L46,50 Z"),
    ("#8FB3CC", "M43,50 L65,50 L65,44 L61,44 L61,47 L57,47 L57,44 L51,44 L51,47 L47,47 L47,44 L43,44 Z"),
    ("#4A6F8A", "M36,82 L72,82 L72,86 L36,86 Z"),
    ("#0B0E13", "M51,82 L51,75 C51,72 57,72 57,75 L57,82 Z"),
    ("#D9776A", "M54,20 C61,28 64,34 61,39 C59,42 57,43 54,43 C51,43 48,42 47,38 C46,34 49,31 51,27 C52,30 53,31 54,32 C55,28 55,25 54,20 Z"),
    ("#F0B3A0", "M54,32 C56,35 58,38 56,40 C55,41 53,41 52,40 C50,38 52,35 54,32 Z"),
]

def parse(d):
    toks = re.findall(r"[MLCZ]|-?[\d.]+", d)
    pts, i, cur = [], 0, (0, 0)
    while i < len(toks):
        t = toks[i]; i += 1
        if t in "ML":
            cur = (float(toks[i]), float(toks[i + 1])); i += 2; pts.append(cur)
        elif t == "C":
            c = [float(x) for x in toks[i:i + 6]]; i += 6
            p0 = cur; p1 = (c[0], c[1]); p2 = (c[2], c[3]); p3 = (c[4], c[5])
            for s in range(1, 17):
                u = s / 16
                x = (1-u)**3*p0[0] + 3*(1-u)**2*u*p1[0] + 3*(1-u)*u**2*p2[0] + u**3*p3[0]
                y = (1-u)**3*p0[1] + 3*(1-u)**2*u*p1[1] + 3*(1-u)*u**2*p2[1] + u**3*p3[1]
                pts.append((x, y))
            cur = p3
    return pts

def render(size, crop=(0, 0, 108, 108), shape="square", ss=4):
    S = size * ss
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if shape == "round":
        d.ellipse([0, 0, S - 1, S - 1], fill=BG)
    elif shape == "rounded":
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.18, fill=BG)
    else:
        d.rectangle([0, 0, S, S], fill=BG)
    x0, y0, x1, y1 = crop
    k = S / (x1 - x0)
    for fill, path in PATHS:
        d.polygon([((x - x0) * k, (y - y0) * k) for x, y in parse(path)], fill=fill)
    return img.resize((size, size), Image.LANCZOS)

def svg():
    body = "\n".join(f'  <path fill="{f}" d="{p}"/>' for f, p in PATHS)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">\n'
            f'  <rect width="108" height="108" fill="{BG}"/>\n{body}\n</svg>\n')

if __name__ == "__main__":
    res = "app/src/main/res"
    legacy_crop = (14, 14, 94, 94)
    for dpi, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        os.makedirs(f"{res}/mipmap-{dpi}", exist_ok=True)
        render(px, legacy_crop, "rounded").save(f"{res}/mipmap-{dpi}/ic_launcher.png")
        render(px, legacy_crop, "round").save(f"{res}/mipmap-{dpi}/ic_launcher_round.png")
    os.makedirs("public/icons", exist_ok=True)
    render(192, (6, 6, 102, 102)).save("public/icons/icon-192.png")
    render(512, (6, 6, 102, 102)).save("public/icons/icon-512.png")
    render(512, (0, 0, 108, 108)).save("public/icons/icon-maskable-512.png")
    render(180, (6, 6, 102, 102)).save("public/icons/apple-touch-icon.png")
    render(512, (0, 0, 108, 108)).convert("RGB").save("public/icons/play-store-512.png")  # Play listing icon (no alpha)
    open("public/icons/icon.svg", "w").write(svg())
    print("icons rendered")
