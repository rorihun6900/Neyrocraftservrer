"""
Background of the character screen (6-row chest window, 176x222 px), drawn in the style of an old
sci-fi RPG terminal: dark navy, cyan frames, a vertical light/dark side bar, a blue-lit portrait.
Run with Blender's Python (numpy):  python gui_character.py  -> gui_character.png

Slot (row r, col c) of the chest is at x = 8 + 18c, y = 18 + 18r (16x16), its frame one pixel around.
Layout used by NeyroKarma.java:
  row 0           tabs (col 0 selected)
  col 0, rows 1-5 light/dark bar (drawn here), col 1 the marker item
  cols 2-4, rows 1-4 the portrait (head, armour, hands)
  cols 5-8, rows 1-4 stats
  cols 2-8, row 5    experience bar
"""
import os
import struct
import zlib

import numpy as np

W, H = 176, 222
img = np.zeros((H, W, 4), np.float64)

NAVY_TOP = np.array([6, 14, 28])
NAVY_BOTTOM = np.array([10, 26, 48])
CYAN = np.array([46, 176, 236])
CYAN_DIM = np.array([22, 84, 128])
GREEN = np.array([70, 236, 96])
PANEL = np.array([7, 20, 38])


def fill(x0, y0, x1, y1, col, alpha=255):
    img[y0:y1 + 1, x0:x1 + 1, :3] = col
    img[y0:y1 + 1, x0:x1 + 1, 3] = alpha


def frame(x0, y0, x1, y1, col, glow=True):
    img[y0, x0:x1 + 1, :3] = col
    img[y1, x0:x1 + 1, :3] = col
    img[y0:y1 + 1, x0, :3] = col
    img[y0:y1 + 1, x1, :3] = col
    if glow:   # a soft inner glow line
        g = col * 0.45 + img[y0 + 1, x0 + 1, :3] * 0.55
        img[y0 + 1, x0 + 1:x1, :3] = g
        img[y1 - 1, x0 + 1:x1, :3] = g
        img[y0 + 1:y1, x0 + 1, :3] = g
        img[y0 + 1:y1, x1 - 1, :3] = g
    for x, y in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):   # rounded corners
        img[y, x, :3] = img[y, x, :3] * 0.3 + NAVY_TOP * 0.7


def vgradient(x0, y0, x1, y1, top, bottom):
    for y in range(y0, y1 + 1):
        t = (y - y0) / max(1, y1 - y0)
        img[y, x0:x1 + 1, :3] = top * (1 - t) + bottom * t
        img[y, x0:x1 + 1, 3] = 255


FONT = {
    "С": ["111", "100", "100", "100", "111"], "В": ["110", "101", "110", "101", "110"],
    "Е": ["111", "100", "110", "100", "111"], "Т": ["111", "010", "010", "010", "010"],
    "Ь": ["100", "100", "110", "101", "110"], "М": ["101", "111", "111", "101", "101"],
    "А": ["010", "101", "111", "101", "101"],
}


def text_vertical(word, x, y, col):
    for ch in word:
        for r, row in enumerate(FONT[ch]):
            for c, bit in enumerate(row):
                if bit == "1":
                    img[y + r, x + c, :3] = col
        y += 6


def slot_frame(r, c, col=CYAN_DIM, fillcol=None):
    x, y = 7 + 18 * c, 17 + 18 * r
    if fillcol is not None:
        fill(x + 1, y + 1, x + 16, y + 16, fillcol)
    frame(x, y, x + 17, y + 17, col, glow=False)


# ---- background
vgradient(0, 0, W - 1, H - 1, NAVY_TOP, NAVY_BOTTOM)
frame(0, 0, W - 1, H - 1, CYAN)

# ---- name bar (the window title is drawn on it by the game)
fill(3, 2, W - 4, 14, np.array([5, 22, 14]))
frame(3, 2, W - 4, 14, GREEN)

# ---- tabs
for c in range(9):
    slot_frame(0, c, GREEN if c == 0 else CYAN_DIM, np.array([10, 40, 22]) if c == 0 else np.array([9, 30, 54]))

# ---- light / dark side bar
fill(7, 35, 24, 124, PANEL)
frame(7, 35, 24, 124, CYAN)
light, mid, dark = np.array([70, 200, 255]), np.array([120, 60, 200]), np.array([235, 30, 40])
for y in range(38, 122):
    t = (y - 38) / (121 - 38)
    col = light * (1 - t * 2) + mid * (t * 2) if t < 0.5 else mid * (2 - t * 2) + dark * (t * 2 - 1)
    for x in range(11, 21):
        edge = 0.65 if x in (11, 20) else 1.0
        img[y, x, :3] = col * edge
text_vertical("СВЕТ", 2, 37, light)
text_vertical("ТЬМА", 2, 98, dark)
for r in range(1, 6):                        # the track of the marker
    slot_frame(r, 1, np.array([16, 50, 80]))

# ---- portrait: blue light from below, like a hologram stage
x0, y0, x1, y1 = 43, 35, 96, 106
vgradient(x0, y0, x1, y1, np.array([6, 22, 60]), np.array([50, 120, 220]))
for y in range(y0, y1 + 1):
    for x in range(x0, x1 + 1):
        dx = (x - (x0 + x1) / 2) / ((x1 - x0) / 2)
        dy = (y1 - y) / (y1 - y0)
        fog = max(0.0, 1 - (dx * dx * 0.8 + dy * 2.2))
        img[y, x, :3] = img[y, x, :3] * (1 - fog) + np.array([200, 230, 255]) * fog
frame(x0, y0, x1, y1, CYAN)

# ---- stats table
fill(97, 35, 170, 106, PANEL)
frame(97, 35, 170, 106, CYAN)
for r in range(1, 5):
    for c in range(5, 9):
        slot_frame(r, c, CYAN_DIM, np.array([9, 28, 50]))

# ---- experience strip
fill(43, 107, 170, 124, PANEL)
frame(43, 107, 170, 124, CYAN)

# ---- the player's own inventory below
for r in range(3):
    for c in range(9):
        x, y = 7 + 18 * c, 139 + 18 * r
        fill(x + 1, y + 1, x + 16, y + 16, np.array([9, 24, 44]))
        frame(x, y, x + 17, y + 17, CYAN_DIM, glow=False)
for c in range(9):
    x, y = 7 + 18 * c, 197
    fill(x + 1, y + 1, x + 16, y + 16, np.array([9, 24, 44]))
    frame(x, y, x + 17, y + 17, CYAN, glow=False)
img[127:136, 3:W - 3, :3] = img[127:136, 3:W - 3, :3] * 0.6     # darker band under the "Inventory" label


def write_png(path, a):
    a = np.clip(a, 0, 255).astype(np.uint8)
    raw = b"".join(b"\x00" + a[y].tobytes() for y in range(a.shape[0]))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", a.shape[1], a.shape[0], 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gui_character.png")
    write_png(out, img)
    print("gui_character.png", W, "x", H)
