#!/usr/bin/env python3
"""
Print Slovak diacritics and the euro sign on an ESC/POS printer that has no
suitable code page, using user-defined characters (ESC &).

How it works:
  1. Each missing glyph (č, š, ž, …, €) is drawn with a TrueType font into a
     12 x 24 dot bitmap (the size of the printer's Font A).
  2. The bitmaps are uploaded with ESC & into free slots of the user-defined
     character set; the slots are ordinary ASCII codes (0x21, 0x22, …).
  3. While printing, the user-defined set is switched on (ESC % 1) only around
     those characters and off again (ESC % 0) for normal text, so the borrowed
     ASCII codes still print normally everywhere else.

Uploaded glyphs are lost on ESC @ / reset / power-off, so they are uploaded
right after ESC @ every time.

Usage:
  ./escpos_udc.py                      # print a test receipt
  ./escpos_udc.py --preview            # only show glyphs in the terminal
  ./escpos_udc.py --text "Cena: 5 €"   # print your own text
"""
import argparse
import sys
import time

import serial
from PIL import Image, ImageDraw, ImageFont

ESC, GS, FS = 0x1B, 0x1D, 0x1C

# Font A cell of the printer (12 x 24 dots). y = height in bytes (24 / 8).
CELL_W, CELL_H = 12, 24

# Non-ASCII characters we want to print. Anything else non-ASCII becomes '?'.
CHARS = "áäčďéíĺľňóôŕšťúýžÁÄČĎÉÍĹĽŇÓÔŔŠŤÚÝŽ€"

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"


def render_glyph(ch: str, font: ImageFont.FreeTypeFont, baseline: int) -> Image.Image:
    """Draw one character centered horizontally into a CELL_W x CELL_H 1-bit image."""
    img = Image.new("1", (CELL_W, CELL_H), 0)
    d = ImageDraw.Draw(img)
    w = d.textlength(ch, font=font)
    d.text(((CELL_W - w) / 2, baseline), ch, font=font, fill=1, anchor="ls")
    return img


def glyph_bytes(img: Image.Image) -> bytes:
    """ESC & data layout: column by column, each column = 3 bytes top->bottom, MSB on top."""
    px = img.load()
    out = bytearray()
    for x in range(CELL_W):
        for yb in range(CELL_H // 8):
            b = 0
            for bit in range(8):
                if px[x, yb * 8 + bit]:
                    b |= 0x80 >> bit
            out.append(b)
    return bytes(out)


def build_glyphs(font_path: str, size: int, baseline: int) -> dict[str, tuple[int, Image.Image]]:
    """Returns {char: (slot code, image)}; slots start at 0x21 ('!')."""
    font = ImageFont.truetype(font_path, size)
    return {ch: (0x21 + i, render_glyph(ch, font, baseline)) for i, ch in enumerate(CHARS)}


def upload_cmd(glyphs) -> bytes:
    """ESC & y c1 c2 [x d1..d(y*x)]...  – define a contiguous range of user characters."""
    codes = sorted(code for code, _ in glyphs.values())
    by_code = {code: img for code, img in glyphs.values()}
    out = bytearray([ESC, ord("&"), CELL_H // 8, codes[0], codes[-1]])
    for code in range(codes[0], codes[-1] + 1):
        out.append(CELL_W)
        out += glyph_bytes(by_code[code])
    return bytes(out)


def encode(text: str, glyphs) -> bytes:
    """Text -> bytes; special characters are wrapped in ESC % 1 ... ESC % 0."""
    out = bytearray()
    user = False
    for ch in text:
        if ch in glyphs:
            if not user:
                out += bytes([ESC, ord("%"), 1])
                user = True
            out.append(glyphs[ch][0])
        else:
            if user:
                out += bytes([ESC, ord("%"), 0])
                user = False
            out += ch.encode("ascii", "replace")
    if user:
        out += bytes([ESC, ord("%"), 0])
    return bytes(out)


def preview(glyphs):
    """Show the bitmaps in the terminal, a few per row."""
    items = list(glyphs.items())
    for i in range(0, len(items), 6):
        row = items[i:i + 6]
        print("   ".join(f"{ch:<{CELL_W}}" for ch, _ in row))
        for y in range(CELL_H):
            print("   ".join("".join("#" if img.getpixel((x, y)) else "." for x in range(CELL_W))
                             for _, (_, img) in row))
        print()


def send(port: serial.Serial, data: bytes):
    """Software DTR/DSR flow control: the printer signals 'ready' on DSR (CTS is unused)."""
    for i in range(0, len(data), 64):
        t = time.time()
        while not port.dsr:
            if time.time() - t > 5:
                sys.exit("Printer not ready (DSR off) – paper / cover?")
            time.sleep(0.01)
        port.write(data[i:i + 64])
        port.flush()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", default="/dev/ttyUSB0")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--font", default=FONT)
    ap.add_argument("--size", type=int, default=19, help="font size in px")
    ap.add_argument("--baseline", type=int, default=19, help="baseline row inside the 24-dot cell")
    ap.add_argument("--text", help="text to print (default: test receipt)")
    ap.add_argument("--preview", action="store_true", help="only show glyphs, don't print")
    args = ap.parse_args()

    glyphs = build_glyphs(args.font, args.size, args.baseline)
    if args.preview:
        preview(glyphs)
        return

    text = args.text or (
        "Príliš žluťoučký kôň\n"
        "úpäl ďábelské ódy.\n"
        "áäčďéíĺľňóôŕšťúýž\n"
        "ÁÄČĎÉÍĹĽŇÓÔŔŠŤÚÝŽ\n"
        "Cena: 12,50 €\n"
    )

    out = bytearray()
    out += bytes([ESC, ord("@")])          # ESC @  – init (also clears user-defined chars)
    out += bytes([FS, ord(".")])           # FS .   – cancel Korean double-byte mode (KS5601)
    out += bytes([ESC, ord("!"), 0])       # ESC !0 – Font A (12 x 24), must match CELL_W/H
    out += upload_cmd(glyphs)              # ESC &  – upload our glyphs
    out += bytes([ESC, ord("a"), 1, GS, ord("!"), 0x11])   # center, double size
    out += encode("Skúška €\n", glyphs)
    out += bytes([GS, ord("!"), 0, ESC, ord("a"), 0])      # normal size, left
    out += encode(text, glyphs)
    out += encode("ASCII stále OK: !\"#$%&'()*+,-./ @[]{}\n", glyphs)
    out += bytes([ESC, ord("d"), 8, GS, ord("V"), 0])      # feed 8 lines, full cut

    p = serial.Serial(args.port, args.baud, timeout=5)
    p.dtr = True
    p.rts = True
    time.sleep(0.1)
    send(p, bytes(out))
    time.sleep(1)  # let the adapter drain before close
    p.close()
    print(f"sent {len(out)} B")


if __name__ == "__main__":
    main()
