#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["resvg-py"]
# ///
"""Generate the etmc icon: two blocks joined on the diagonal by a short link.

Two peers, one direct connection; each block brings its own half of the link. Flat
shapes, sharp corners, no lettering, in the site palette (``site/src/styles/global.css``:
dark panel, aqua).
Nothing borrowed from Minecraft: its grass block and logo are Mojang's trade dress.

The SVG is the source; PNGs are rasterised from it with resvg, so every output is the
same image. Run from anywhere::

    uv run tools/gen_icon.py

Writes:
    common/src/main/resources/assets/etmc/icon.png   256px; Fabric ``icon`` + Forge/NeoForge ``logoFile``
    site/public/favicon.svg                          the vector source
    site/public/icon-512.png                         README / apple-touch-icon / social
"""
from pathlib import Path

import resvg_py

ROOT = Path(__file__).resolve().parent.parent

TILE, HOST, PEER = '#1f1f26', '#f4f1e8', '#62d0e0'


def icon():
    c = 256                   # everything is laid out on a horizontal axis, then turned 45°
    a, b = c - 104, c + 104   # block centres
    size = 168                # blocks
    bar = 52                  # link thickness
    h = size / 2
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <rect width="512" height="512" fill="{TILE}"/>
  <g transform="rotate(-45 {c} {c})">
    <rect x="{a}" y="{c - bar / 2}" width="{b - a}" height="{bar}" fill="{HOST}"/>
    <rect x="{c}" y="{c - bar / 2}" width="{b - c}" height="{bar}" fill="{PEER}"/>
    <rect x="{a - h}" y="{c - h}" width="{size}" height="{size}" fill="{HOST}"/>
    <rect x="{b - h}" y="{c - h}" width="{size}" height="{size}" fill="{PEER}"/>
  </g>
</svg>
'''


def main():
    svg = icon()
    png = lambda size: bytes(resvg_py.svg_to_bytes(svg_string=svg, width=size, height=size))
    outputs = {
        'common/src/main/resources/assets/etmc/icon.png': png(256),
        'site/public/icon-512.png': png(512),
        'site/public/favicon.svg': svg.encode(),
    }
    for rel, data in outputs.items():
        path = ROOT / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        print(f'wrote {rel} ({len(data)} bytes)')


if __name__ == '__main__':
    main()
