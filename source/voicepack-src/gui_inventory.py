"""
Art for the NeyroCraft pack, drawn by code (run with Blender's Python, it has numpy):
    python gui_inventory.py
  inventory.png      the player's own inventory (E) in the style of an old sci-fi RPG terminal:
                     dark navy, cyan frames, the player stands on a blue hologram stage
  clone_trooper.png  skin of the Neyro-City guards: white armour, black T visor, blue markings
  side_light.png / side_dark.png   18x18 icons of the light / dark side (shown as an effect)

The inventory texture keeps the vanilla layout exactly (176x166 in a 256x256 file):
player box 26..75 x 8..78, armour slots at (8, 8 + 18i), offhand (77, 62), crafting 2x2 at
(98|116, 18|36), result (154, 28), inventory (8 + 18c, 84 + 18r), hotbar (8 + 18c, 142).
"""
import os
import struct
import zlib

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))

NAVY_TOP = np.array([6, 14, 28])
NAVY_BOTTOM = np.array([10, 26, 48])
CYAN = np.array([46, 176, 236])
CYAN_DIM = np.array([22, 84, 128])
GREEN = np.array([70, 236, 96])
SLOT = np.array([9, 24, 44])


def write_png(path, a):
    a = np.clip(a, 0, 255).astype(np.uint8)
    raw = b"".join(b"\x00" + a[y].tobytes() for y in range(a.shape[0]))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", a.shape[1], a.shape[0], 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


class Canvas:
    def __init__(self, w, h):
        self.a = np.zeros((h, w, 4), np.float64)

    def fill(self, x0, y0, x1, y1, col, alpha=255):
        self.a[y0:y1 + 1, x0:x1 + 1, :3] = col
        self.a[y0:y1 + 1, x0:x1 + 1, 3] = alpha

    def px(self, x, y, col, alpha=255):
        self.a[y, x, :3] = col
        self.a[y, x, 3] = alpha

    def frame(self, x0, y0, x1, y1, col, glow=True, round_=True):
        a = self.a
        for (xa, ya, xb, yb) in ((x0, y0, x1, y0), (x0, y1, x1, y1), (x0, y0, x0, y1), (x1, y0, x1, y1)):
            a[ya:yb + 1, xa:xb + 1, :3] = col
            a[ya:yb + 1, xa:xb + 1, 3] = 255
        if glow:
            g = col * 0.45 + a[y0 + 1, x0 + 1, :3] * 0.55
            a[y0 + 1, x0 + 1:x1, :3] = g
            a[y1 - 1, x0 + 1:x1, :3] = g
            a[y0 + 1:y1, x0 + 1, :3] = g
            a[y0 + 1:y1, x1 - 1, :3] = g
        if round_:
            for x, y in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
                a[y, x, :3] = a[y, x, :3] * 0.3 + NAVY_TOP * 0.7

    def vgradient(self, x0, y0, x1, y1, top, bottom):
        for y in range(y0, y1 + 1):
            t = (y - y0) / max(1, y1 - y0)
            self.a[y, x0:x1 + 1, :3] = top * (1 - t) + bottom * t
            self.a[y, x0:x1 + 1, 3] = 255

    def slot(self, x, y, border=CYAN_DIM):
        """A slot whose item sits at (x, y): 16x16 well with a one-pixel frame around."""
        self.fill(x, y, x + 15, y + 15, SLOT)
        self.frame(x - 1, y - 1, x + 16, y + 16, border, glow=False, round_=False)
        self.a[y, x:x + 16, :3] *= 0.7                 # inner shadow at the top-left
        self.a[y:y + 16, x, :3] *= 0.7


LIGHT = np.array([70, 200, 255])
MID = np.array([120, 60, 200])
DARK = np.array([235, 30, 40])
LETTERS = {"С": ["111", "100", "100", "100", "111"], "Т": ["111", "010", "010", "010", "010"]}


def side_colour(t):
    return LIGHT * (1 - t * 2) + MID * (t * 2) if t < 0.5 else MID * (2 - t * 2) + DARK * (t * 2 - 1)


def side_bar(c, x, y_top, y_bottom):
    """The light/dark side bar: С(вет) on top, a blue -> violet -> red gradient, Т(ьма) below."""
    for r, row in enumerate(LETTERS["С"]):
        for k, bit in enumerate(row):
            if bit == "1":
                c.px(x + 1 + k, y_top + r, LIGHT)
    for r, row in enumerate(LETTERS["Т"]):
        for k, bit in enumerate(row):
            if bit == "1":
                c.px(x + 1 + k, y_bottom + 2 + r, DARK)
    b0, b1 = y_top + 7, y_bottom
    c.fill(x, b0 - 1, x + 5, b1 + 1, np.array([5, 14, 30]))
    for yy in range(b0, b1 + 1):
        col = side_colour((yy - b0) / (b1 - b0))
        for xx in range(x + 1, x + 5):
            c.px(xx, yy, col * (0.7 if xx in (x + 1, x + 4) else 1.0))
    c.frame(x, b0 - 1, x + 5, b1 + 1, CYAN_DIM, glow=False, round_=False)


# ====================================================================== inventory
def inventory():
    c = Canvas(256, 256)
    W, H = 176, 166
    c.vgradient(0, 0, W - 1, H - 1, NAVY_TOP, NAVY_BOTTOM)
    c.frame(0, 0, W - 1, H - 1, CYAN)

    # the hologram stage the player stands on
    x0, y0, x1, y1 = 25, 7, 76, 79
    c.vgradient(x0 + 1, y0 + 1, x1 - 1, y1 - 1, np.array([6, 22, 60]), np.array([50, 120, 220]))
    for y in range(y0 + 1, y1):
        for x in range(x0 + 1, x1):
            dx = (x - (x0 + x1) / 2) / ((x1 - x0) / 2)
            dy = (y1 - y) / (y1 - y0)
            fog = max(0.0, 1 - (dx * dx * 0.8 + dy * 2.4))
            c.a[y, x, :3] = c.a[y, x, :3] * (1 - fog) + np.array([200, 230, 255]) * fog
    for y in range(y0 + 3, y1 - 1, 3):                  # faint scan lines
        c.a[y, x0 + 2:x1 - 1, :3] *= 0.9
    c.frame(x0, y0, x1, y1, CYAN)
    side_bar(c, 27, 10, 70)

    # armour column and offhand
    for i in range(4):
        c.slot(8, 8 + 18 * i, CYAN)
    c.slot(77, 62, CYAN)

    # crafting panel (the game writes "Crafting" at 97, 8)
    c.fill(88, 5, 171, 64, np.array([7, 20, 38]))
    c.frame(88, 5, 171, 64, CYAN_DIM)
    c.fill(90, 6, 152, 15, np.array([5, 22, 14]))
    c.frame(89, 5, 153, 16, GREEN, glow=False)
    for (x, y) in ((98, 18), (116, 18), (98, 36), (116, 36)):
        c.slot(x, y)
    # arrow
    for i in range(12):
        c.fill(136 + i, 34, 136 + i, 35, CYAN)
    for k in range(5):
        c.fill(147 - 4 + k, 30 + k, 147 - 4 + k, 39 - k, CYAN)
    # result slot with a glowing frame
    c.fill(150, 24, 173 - 2, 47, np.array([10, 40, 22]))
    c.frame(149, 23, 172, 48, GREEN)
    c.slot(154, 28, GREEN)

    # main inventory and hotbar
    c.fill(4, 81, 171, 137, np.array([7, 20, 38]))
    c.frame(4, 81, 171, 137, CYAN_DIM)
    for r in range(3):
        for col in range(9):
            c.slot(8 + 18 * col, 84 + 18 * r)
    c.fill(4, 139, 171, 161, np.array([8, 30, 54]))
    c.frame(4, 139, 171, 161, CYAN)
    for col in range(9):
        c.slot(8 + 18 * col, 142, CYAN)
    write_png(os.path.join(HERE, "inventory.png"), c.a)


# ====================================================================== clone trooper skin
WHITE = np.array([236, 238, 240])
SHADE = np.array([196, 200, 206])
GREY = np.array([150, 156, 166])
BLACK = np.array([22, 22, 26])
BLUE = np.array([70, 120, 205])
BLUE_D = np.array([44, 82, 160])


def face(c, x, y, w, h, base, rows=None):
    """Fills a w x h face at (x, y); rows: optional function (cx, cy) -> colour or None."""
    for j in range(h):
        for i in range(w):
            col = base
            if rows is not None:
                r = rows(i, j)
                if r is not None:
                    col = r
            if col is None:
                continue
            c.px(x + i, y + j, col)


def box(c, ox, oy, w, h, d, fn):
    """A Minecraft skin cuboid at texture origin (ox, oy) with size w x h x d.
    fn(side, i, j) -> colour (side: top, bottom, right, front, left, back)."""
    sides = {
        "top": (ox + d, oy, w, d), "bottom": (ox + d + w, oy, w, d),
        "right": (ox, oy + d, d, h), "front": (ox + d, oy + d, w, h),
        "left": (ox + d + w, oy + d, d, h), "back": (ox + d + w + d, oy + d, w, h),
    }
    for s, (x, y, ww, hh) in sides.items():
        for j in range(hh):
            for i in range(ww):
                col = fn(s, i, j, ww, hh)
                if col is not None:
                    c.px(x + i, y + j, col)


def helmet(s, i, j, w, h):
    if s == "top":
        return BLUE if i in (3, 4) and j >= 1 else WHITE            # the ridge on top
    if s == "bottom":
        return GREY
    if s == "front":
        if j == 3:
            return BLACK                                           # T visor: the bar, full width
        if j in (4, 5, 6) and i in (3, 4):
            return BLACK                                           # ...and the stem
        if j == 2 and i in (0, 1, 6, 7):
            return BLUE                                            # markings over the eyes
        if j in (4, 5) and i in (1, 6):
            return BLUE_D                                          # cheek stripes
        if j == 7:
            return GREY if i in (3, 4) else SHADE
        return WHITE if j < 4 else SHADE
    if s in ("left", "right"):
        if j == 3 and 2 <= i <= 5:
            return GREY                                            # ear piece
        if j in (5, 6) and i >= 5:
            return BLUE_D
        return WHITE if j < 5 else SHADE
    if s == "back":
        return BLUE if j == 1 and 2 <= i <= 5 else (WHITE if j < 6 else SHADE)
    return WHITE


def torso(s, i, j, w, h):
    if s in ("top", "bottom"):
        return GREY if s == "bottom" else WHITE
    if j in (8, 9):                                               # belt
        if s == "front" and i in (3, 4) and j == 8:
            return GREY
        return SHADE if (i % 2 == 0) else WHITE
    if j >= 10:
        if s == "front" and 2 <= i <= 5 and j == 11:
            return WHITE                                           # cod plate
        return BLACK                                               # bodysuit between belt and legs
    if s == "front":
        if j == 0 and 2 <= i <= 5:
            return BLACK                                           # collar
        if i in (3, 4) and 1 <= j <= 4:
            return SHADE                                           # chest split
        if j == 5:
            return GREY                                            # plate seam
        return WHITE if j < 5 else SHADE
    if s == "back":
        if j == 5 or (i in (3, 4) and j < 5):
            return GREY
        return WHITE if j < 5 else SHADE
    return BLACK if j in (5, 6) else SHADE                         # sides: bodysuit shows


def arm(s, i, j, w, h):
    if s == "top":
        return BLUE                                                # pauldron
    if s == "bottom":
        return BLACK
    if j == 0:
        return BLUE                                                # blue edge of the pauldron
    if j <= 2:
        return BLUE_D if s in ("right", "left") and j == 1 else WHITE
    if j == 3:
        return BLACK                                               # under the pauldron
    if j <= 5:
        return WHITE                                               # upper arm plate
    if j == 6:
        return BLACK
    if j <= 10:
        return BLUE if (j + i) % 5 else BLUE_D                     # forearm
    return BLACK                                                   # glove


def leg(s, i, j, w, h):
    if s == "top":
        return BLACK
    if s == "bottom":
        return GREY
    if j == 0:
        return BLACK
    if j in (5, 6):
        return BLACK if s != "front" or j == 6 else GREY          # knee
    if j >= 10:
        return SHADE if j == 10 else GREY                          # boots
    return WHITE if s == "front" else SHADE


def trooper():
    c = Canvas(64, 64)
    box(c, 0, 0, 8, 8, 8, helmet)                                  # head
    box(c, 32, 0, 8, 8, 8, helmet)                                 # helmet shell (outer layer)
    box(c, 16, 16, 8, 12, 4, torso)                                # body
    box(c, 40, 16, 4, 12, 4, arm)                                  # right arm
    box(c, 32, 48, 4, 12, 4, arm)                                  # left arm
    box(c, 0, 16, 4, 12, 4, leg)                                   # right leg
    box(c, 16, 48, 4, 12, 4, leg)                                  # left leg
    # outer layer: chest and thigh plates stand out a little
    box(c, 16, 32, 8, 12, 4, lambda s, i, j, w, h: (WHITE if j < 5 and s == "front" and i not in (3, 4) else None))
    box(c, 0, 32, 4, 12, 4, lambda s, i, j, w, h: (WHITE if 1 <= j <= 4 and s == "front" else None))
    box(c, 0, 48, 4, 12, 4, lambda s, i, j, w, h: (WHITE if 1 <= j <= 4 and s == "front" else None))
    write_png(os.path.join(HERE, "clone_trooper.png"), c.a)


# ====================================================================== side icons
def icon(name, marker_y, glow):
    """18x18: a little light/dark scale with an arrow pointing at the player's side."""
    c = Canvas(18, 18)
    c.fill(1, 0, 16, 17, np.array([6, 16, 32]))
    c.frame(0, 0, 17, 17, glow, glow=False)
    for yy in range(2, 16):                                  # the scale
        col = side_colour((yy - 2) / 13)
        for xx in range(3, 7):
            c.px(xx, yy, col * (0.75 if xx in (3, 6) else 1.0))
    for k in range(5):                                        # the arrow, pointing left at the scale
        for yy in range(marker_y - (4 - k) // 2, marker_y + (4 - k) // 2 + 1):
            c.px(8 + k, yy, np.array([255, 255, 255]) if k < 2 else glow)
    for xx in range(13, 16):
        c.px(xx, marker_y, glow)
    write_png(os.path.join(HERE, "side_%s.png" % name), c.a)


def side_icons():
    icon("light", 4, LIGHT)
    icon("dark", 13, DARK)


# ====================================================================== HUD
def hud():
    out = os.path.join(HERE, "hud")
    os.makedirs(out, exist_ok=True)
    # hotbar 182x22: nine wells, item of slot i at (3 + 20i, 3)
    c = Canvas(182, 22)
    c.fill(0, 0, 181, 21, np.array([6, 16, 32]), 225)
    c.frame(0, 0, 181, 21, CYAN, glow=False)
    for i in range(9):
        x = 3 + 20 * i
        c.fill(x - 1, 2, x + 16, 19, SLOT, 235)
        c.frame(x - 2, 1, x + 17, 20, CYAN_DIM, glow=False, round_=False)
    write_png(os.path.join(out, "hotbar.png"), c.a)
    # selection 24x23, drawn at (20i - 1, -1)
    c = Canvas(24, 23)
    c.frame(0, 0, 23, 22, GREEN, glow=False)
    c.frame(1, 1, 22, 21, GREEN * 0.6, glow=False, round_=False)
    write_png(os.path.join(out, "hotbar_selection.png"), c.a)
    # offhand wells 29x24: item at (3, 4) on the left one, (10, 4) on the right one
    for name, ix in (("hotbar_offhand_left", 3), ("hotbar_offhand_right", 10)):
        c = Canvas(29, 24)
        c.fill(ix - 2, 2, ix + 17, 21, np.array([6, 16, 32]), 225)
        c.frame(ix - 3, 1, ix + 18, 22, CYAN, glow=False)
        c.fill(ix - 1, 3, ix + 16, 20, SLOT, 235)
        write_png(os.path.join(out, name + ".png"), c.a)
    # experience bar 182x5
    c = Canvas(182, 5)
    c.fill(0, 0, 181, 4, np.array([5, 14, 28]), 230)
    c.frame(0, 0, 181, 4, CYAN_DIM, glow=False, round_=False)
    write_png(os.path.join(out, "experience_bar_background.png"), c.a)
    write_png(os.path.join(out, "locator_bar_background.png"), c.a)
    c = Canvas(182, 5)
    for x in range(182):
        t = x / 181
        col = np.array([40, 190, 255]) * (1 - t) + np.array([110, 255, 140]) * t
        c.fill(x, 0, x, 4, col * 0.75)
        c.px(x, 1, np.minimum(col * 1.15, 255))
        c.px(x, 2, col)
    write_png(os.path.join(out, "experience_bar_progress.png"), c.a)
    # effect backgrounds: on the HUD (24x24, ambient = the light/dark side) and in the inventory
    for name, w, h, col in (("effect_background", 24, 24, CYAN), ("effect_background_ambient", 24, 24, np.array([120, 90, 230])),
                            ("effect_background_large", 120, 32, CYAN), ("effect_background_small", 32, 32, CYAN)):
        c = Canvas(w, h)
        c.vgradient(0, 0, w - 1, h - 1, np.array([6, 16, 32]), np.array([10, 28, 52]))
        c.frame(0, 0, w - 1, h - 1, col)
        write_png(os.path.join(out, name + ".png"), c.a)


if __name__ == "__main__":
    inventory()
    trooper()
    side_icons()
    hud()
    print("inventory.png, clone_trooper.png, side_light.png, side_dark.png, hud/*.png")
