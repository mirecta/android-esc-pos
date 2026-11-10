#!/usr/bin/env python3
"""
Print any text (Slovak/Czech diacritics, euro sign, ...) on an ESC/POS printer
by rendering it to a bitmap and sending it as a raster image (GS v 0).

Works on any printer that supports GS v 0, regardless of its code pages.
Verified on REXOD RX831-H120 (RMF8300, firmware V1.73T, KS5601 font).

Usage:
  ./escpos_raster.py                         # print a test receipt
  ./escpos_raster.py --text "Cena: 5 €"      # print your own text
"""
import argparse
import sys
import time

import serial
from PIL import Image, ImageDraw, ImageFont

GS, ESC = 0x1D, 0x1B

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def render(title: str, lines: list[str], width: int) -> Image.Image:
    """Draw a centered bold title and left-aligned lines into a 1-bit image."""
    big = ImageFont.truetype(FONT_BOLD, 44)
    norm = ImageFont.truetype(FONT, 30)
    img = Image.new("1", (width, 4000), 1)  # 1 = white
    d = ImageDraw.Draw(img)
    y = 0
    d.text(((width - d.textlength(title, font=big)) / 2, y), title, font=big, fill=0)
    y += int(big.size * 1.3)
    for s in lines:
        d.text((0, y), s, font=norm, fill=0)
        y += int(norm.size * 1.3)
    return img.crop((0, 0, width, y))


def raster_cmd(img: Image.Image) -> bytes:
    """GS v 0 m xL xH yL yH d...  – 1 bit per dot, rows top to bottom, MSB = leftmost dot."""
    wb = (img.width + 7) // 8  # bytes per row
    px = img.load()
    data = bytearray()
    for y in range(img.height):
        for xb in range(wb):
            b = 0
            for bit in range(8):
                x = xb * 8 + bit
                if x < img.width and px[x, y] == 0:  # black pixel -> printed dot
                    b |= 0x80 >> bit
            data.append(b)
    return bytes([GS, ord("v"), ord("0"), 0, wb & 0xFF, wb >> 8,
                  img.height & 0xFF, img.height >> 8]) + data


def send(port: serial.Serial, data: bytes):
    """Software DTR/DSR flow control: the printer signals 'ready' on DSR (DIP switch 3 = DTR)."""
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
    ap.add_argument("--width", type=int, default=576, help="printable width in dots (self-test: TPH Area)")
    ap.add_argument("--title", default="ESC/POS TEST")
    ap.add_argument("--text", help="text to print, \\n separates lines (default: test text)")
    args = ap.parse_args()

    text = args.text or "Príliš žluťoučký kôň\núpäl ďábelské ódy.\nČĎĹĽŇŔŠŤŽ áäéíóôú\nCena: 12,50 €"
    img = render(args.title, text.split("\n"), args.width)

    out = bytes([ESC, ord("@")])                       # ESC @   – init
    out += raster_cmd(img)                             # GS v 0  – the text as an image
    out += bytes([ESC, ord("d"), 8, GS, ord("V"), 0])  # feed 8 lines, full cut

    p = serial.Serial(args.port, args.baud, timeout=5)
    p.dtr = True
    p.rts = True
    time.sleep(0.1)
    send(p, out)
    time.sleep(2)  # let the adapter drain before close
    p.close()
    print(f"image {img.width}x{img.height}, sent {len(out)} B")


if __name__ == "__main__":
    main()
