#!/usr/bin/env python3
"""Overlays column/row index labels on a DawnLike-style tile sheet, so a specific tile can be
called out as (sheetX, sheetY) in tile-grid units — the same coordinate system
kotile's StaticTile(sheetX, sheetY) takes. Dev aid only, not part of the build.

Usage:
    demo/scripts/label-tilesheet.py <sheet.png> [--tile-size 16] [--scale 3] [--out labeled.png]
"""
import argparse
import sys

from PIL import Image, ImageDraw, ImageFont

MARGIN = 18
GRID_COLOR = (255, 0, 255, 180)
LABEL_COLOR = (255, 255, 0, 255)
BG_COLOR = (20, 20, 20, 255)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sheet", help="Path to the source tile sheet PNG")
    parser.add_argument("--tile-size", type=int, default=16, help="Tile size in source px (default 16)")
    parser.add_argument("--scale", type=int, default=3, help="Upscale factor for legibility (default 3)")
    parser.add_argument("--out", default=None, help="Output path (default: <sheet>.labeled.png)")
    parser.add_argument(
        "--rows",
        default=None,
        help="Only render rows START:END (0-indexed, END exclusive) — labels stay absolute, "
        "so coordinates read off a chunk still match the full sheet. Splits a tall sheet "
        "into legible pieces.",
    )
    parser.add_argument("--cols", default=None, help="Only render cols START:END (0-indexed, END exclusive).")
    args = parser.parse_args()

    src = Image.open(args.sheet).convert("RGBA")
    total_cols = src.width // args.tile_size
    total_rows = src.height // args.tile_size
    if src.width % args.tile_size or src.height % args.tile_size:
        print(f"warning: {src.width}x{src.height} isn't an exact multiple of {args.tile_size}px", file=sys.stderr)

    row_start, row_end = 0, total_rows
    if args.rows:
        row_start, row_end = (int(part) for part in args.rows.split(":"))
    col_start, col_end = 0, total_cols
    if args.cols:
        col_start, col_end = (int(part) for part in args.cols.split(":"))

    cell = args.tile_size * args.scale
    crop = src.crop(
        (col_start * args.tile_size, row_start * args.tile_size, col_end * args.tile_size, row_end * args.tile_size),
    )
    scaled = crop.resize((crop.width * args.scale, crop.height * args.scale), Image.NEAREST)

    out = Image.new("RGBA", (scaled.width + MARGIN, scaled.height + MARGIN), BG_COLOR)
    out.paste(scaled, (MARGIN, MARGIN))
    draw = ImageDraw.Draw(out)
    font = ImageFont.load_default()

    for c in range(col_end - col_start + 1):
        x = MARGIN + c * cell
        draw.line([(x, MARGIN), (x, out.height)], fill=GRID_COLOR, width=1)
    for r in range(row_end - row_start + 1):
        y = MARGIN + r * cell
        draw.line([(MARGIN, y), (out.width, y)], fill=GRID_COLOR, width=1)

    for c in range(col_end - col_start):
        draw.text((MARGIN + c * cell + 2, 2), str(col_start + c), fill=LABEL_COLOR, font=font)
    for r in range(row_end - row_start):
        draw.text((2, MARGIN + r * cell + 2), str(row_start + r), fill=LABEL_COLOR, font=font)

    out_path = args.out or (args.sheet.rsplit(".", 1)[0] + ".labeled.png")
    out.save(out_path)
    print(
        f"{total_cols}x{total_rows} tiles (cols {col_start}:{col_end}, rows {row_start}:{row_end} shown) "
        f"-> {out_path}",
    )


if __name__ == "__main__":
    main()
