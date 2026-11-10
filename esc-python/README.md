# esc-python

Python test scripts (PC + PL2303 on `/dev/ttyUSB0`) for printing Slovak/Czech diacritics and `€`.

Requirements: `pip install pyserial pillow`, DejaVu fonts, user in the `dialout` group.

| Script | Method | Status on REXOD RX831-H120 (fw V1.73T) |
|---|---|---|
| `escpos_raster.py` | Text rendered to a bitmap, sent as raster image (`GS v 0`) | **Works** |
| `escpos_udc.py` | Missing glyphs uploaded as user-defined characters (`ESC &`, `ESC %`) | Not supported – the printer's manual doesn't list these commands |

This printer (KS5601 font) has no code page with Slovak letters, so plain text with
diacritics can't be printed by any standard command. Options: raster image (above),
or a firmware/font with a Central European code page from the manufacturer.
