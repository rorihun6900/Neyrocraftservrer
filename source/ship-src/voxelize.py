"""
Turns the Blender model of NEYRO-1 into Minecraft blocks.

Run:  blender neyro1.blend --background --python voxelize.py
      (add  -- --no-render  to skip the preview pictures)

Writes ../neyrohub-src/resources/ship/neyro1.ship.gz, which the NeyroHub plugin builds in the hub
world (on first start with a new BUILD_VERSION, or with /neyrohub rebuild), and preview pictures
of the result in renders/.

Coordinates: see build_ship.py. Only the outer shell is exported (the hull is hollow inside),
except behind glass, where a dark inner layer with a few console lights is added.
Inside the deck radius nothing is placed at or above the floor: the plugin builds the deck itself.
"""
import gzip
import math
import os
import sys
import time

import bpy
import bmesh
import numpy as np
from mathutils import Vector
from mathutils.bvhtree import BVHTree

HERE = os.path.dirname(os.path.abspath(bpy.data.filepath or __file__))
OUT = os.path.join(HERE, "..", "neyrohub-src", "resources", "ship", "neyro1.ship.gz")
# the complete ship (with the deck) hanging in the sky over the survival city
OUT_SKY = os.path.join(HERE, "..", "neyrohub-src", "resources", "ship", "neyro1_sky.ship.gz")
RENDERS = os.path.join(HERE, "renders")
ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []

X0, X1 = -100, 100
Y0, Y1 = -115, 115
Z0, Z1 = -26, 34
NX, NY, NZ = X1 - X0 + 1, Y1 - Y0 + 1, Z1 - Z0 + 1
DECK_R = 25.5          # nothing at or above the floor inside this radius (deck wall is at 24)


def jhash(x, y, z):
    """Same hash as NeyroHub.hash (Java int arithmetic)."""
    def i32(v):
        v &= 0xFFFFFFFF
        return v - (1 << 32) if v & 0x80000000 else v
    h = i32(i32(x * 73856093) ^ i32(y * 19349663) ^ i32(z * 83492791))
    h = i32(h ^ ((h & 0xFFFFFFFF) >> 13))
    h = i32(h * 0x5bd1e995)
    return (h ^ ((h & 0xFFFFFFFF) >> 15)) % 1000


# ------------------------------------------------------------------ voxelization

def object_cells(obj, depsgraph):
    """All block centres inside the closed mesh of obj (ray parity along +Z, column by column)."""
    ev = obj.evaluated_get(depsgraph)
    me = ev.to_mesh()
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.transform(obj.matrix_world)
    ev.to_mesh_clear()
    bvh = BVHTree.FromBMesh(bm)
    xs = [v.co.x for v in bm.verts]
    ys = [v.co.y for v in bm.verts]
    zs = [v.co.z for v in bm.verts]
    bm.free()
    up = Vector((0, 0, 1))
    cells = []
    for x in range(math.ceil(min(xs)) - 1, math.floor(max(xs)) + 2):
        for y in range(math.ceil(min(ys)) - 1, math.floor(max(ys)) + 2):
            o = Vector((x + 0.0123, y + 0.0071, min(zs) - 2))
            hits = []
            while len(hits) < 64:
                loc, _n, _i, _d = bvh.ray_cast(o, up)
                if loc is None:
                    break
                hits.append(loc.z)
                o = Vector((o.x, o.y, loc.z + 1e-4))
            for k in range(0, len(hits) - 1, 2):
                lo, hi = hits[k], hits[k + 1]
                for z in range(math.ceil(lo), math.floor(hi) + 1):
                    if lo < z < hi:
                        cells.append((x, y, z))
    return cells


def voxelize():
    depsgraph = bpy.context.evaluated_depsgraph_get()
    parts = [o for o in bpy.data.objects
             if o.type == "MESH" and "mc_role" in o and not o.get("mc_ignore")]
    occ = np.zeros((NX, NY, NZ), bool)
    owner = np.full((NX, NY, NZ), -1, np.int16)
    prio = np.zeros((NX, NY, NZ), np.int16)
    glow = {}
    info = []
    for k, obj in enumerate(parts):
        cells = np.array(object_cells(obj, depsgraph), dtype=np.int32).reshape(-1, 3)
        ok = ((cells[:, 0] >= X0) & (cells[:, 0] <= X1) & (cells[:, 1] >= Y0) & (cells[:, 1] <= Y1)
              & (cells[:, 2] >= Z0) & (cells[:, 2] <= Z1))
        if not ok.all():
            print("WARNING: %s sticks out of the build box, %d cells cut" % (obj.name, (~ok).sum()))
        cells = cells[ok]
        # hollow glowing exhaust at the back end
        nozzle = obj.get("mc_nozzle")
        if nozzle is not None and len(cells):
            cx, cz, rin, depth = [float(v) for v in nozzle]
            ymin = cells[:, 1].min()
            dist2 = (cells[:, 0] - cx) ** 2 + (cells[:, 2] - cz) ** 2
            inner = dist2 < rin * rin
            for c in cells[inner & (cells[:, 1] == ymin + depth)]:
                glow[tuple(int(v) for v in c)] = "sea_lantern"
            cells = cells[~(inner & (cells[:, 1] < ymin + depth))]
        ix, iy, iz = cells[:, 0] - X0, cells[:, 1] - Y0, cells[:, 2] - Z0
        p = int(obj.get("mc_priority", 1))
        occ[ix, iy, iz] = True
        win = prio[ix, iy, iz] < p
        owner[ix[win], iy[win], iz[win]] = k
        prio[ix[win], iy[win], iz[win]] = p
        info.append({"obj": obj, "role": obj["mc_role"], "cells": cells})
        print("  %-28s %-8s %6d blocks" % (obj.name, obj["mc_role"], len(cells)))

    # keep the deck free: the plugin builds floor, wall, dome and portals there
    gx = (np.arange(NX) + X0)[:, None, None]
    gy = (np.arange(NY) + Y0)[None, :, None]
    gz = (np.arange(NZ) + Z0)[None, None, :]
    carve = (gz >= 0) & (gx * gx + gy * gy < DECK_R * DECK_R)
    occ &= ~carve
    return parts, info, occ, owner, glow


def shell_and_normals(occ):
    P = np.pad(occ, 1)
    inside = np.ones_like(occ)
    n = np.zeros((3,) + occ.shape, np.float32)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == dy == dz == 0:
                    continue
                nb = P[1 + dx:1 + dx + NX, 1 + dy:1 + dy + NY, 1 + dz:1 + dz + NZ]
                if abs(dx) + abs(dy) + abs(dz) == 1:
                    inside &= nb
                empty = (~nb).astype(np.float32)
                n[0] += dx * empty
                n[1] += dy * empty
                n[2] += dz * empty
    return occ & ~inside, n


# ------------------------------------------------------------------ painting

def extents(info):
    """Per-part helper tables: leading/trailing edge of the wings, top edge of the fin, tips."""
    for d in info:
        c = d["cells"]
        d["ymax"] = int(c[:, 1].max()) if len(c) else 0
        d["ymin"] = int(c[:, 1].min()) if len(c) else 0
        d["zmax"] = int(c[:, 2].max()) if len(c) else 0
        front, back, top = {}, {}, {}
        for x, y, z in c:
            front[x] = max(front.get(x, -999), y)
            back[x] = min(back.get(x, 999), y)
            top[y] = max(top.get(y, -999), z)
        d["front"], d["back"], d["top"] = front, back, top


FONT = {   # 5 rows high pixel letters, top row first
    "N": ["1001", "1101", "1011", "1001", "1001"],
    "E": ["111", "100", "110", "100", "111"],
    "Y": ["101", "101", "010", "010", "010"],
    "R": ["110", "101", "110", "101", "101"],
    "O": ["111", "101", "101", "101", "111"],
    "-": ["00", "00", "11", "00", "00"],
    "1": ["010", "110", "010", "010", "111"],
}
SHIP_NAME = "NEYRO-1"
NAME_TOP_Y = -48          # the name on both wings, read from above with the nose up


def wing_text(x, y):
    """Block of the ship's name painted on the top of the wings, or None."""
    scale = 2                                 # every pixel is 2x2 blocks

    def gap(i):                               # 1 block between letters, none around the hyphen
        nxt = SHIP_NAME[i + 1] if i + 1 < len(SHIP_NAME) else ""
        return 0 if not nxt or "-" in (SHIP_NAME[i], nxt) else 1

    width = sum(len(FONT[c][0]) * scale + gap(i) for i, c in enumerate(SHIP_NAME))
    # between the hull (|x| ~ 33) and the wing engines (|x| ~ 79), same place on both wings
    x0 = 33 if x > 0 else -33 - width + 1
    col, row = x - x0, NAME_TOP_Y - y
    if col < 0 or row < 0 or row >= 5 * scale:
        return None
    for ci, ch in enumerate(SHIP_NAME):
        w = len(FONT[ch][0]) * scale
        if col < w:
            if FONT[ch][row // scale][col // scale] != "1":
                return None
            return "light_blue_concrete" if ci >= 5 else "orange_concrete"   # Neyro orange, "-1" blue
        col -= w + gap(ci)
        if col < 0:
            return None
    return None


def paint(d, x, y, z, nx, ny, nz):
    role = d["role"]
    h = jhash(x, z, -y)
    top, bottom, side = nz > 0.55, nz < -0.55, -0.55 <= nz <= 0.55
    if role == "hull":
        if top:
            if y > 28 and abs(x) in (4, 5):
                return "orange_concrete"                      # racing stripes on the nose
            if y % 12 == 0 or (x % 12 == 0 and x != 0):
                return "light_gray_concrete"                  # panel seams
            return "white_concrete"
        if bottom:
            if h % 53 == 0:
                return "shroomlight"                          # belly lights
            if y % 8 == 0 or x % 8 == 0:
                return "light_gray_concrete"
            return "gray_concrete"
        if z == -3:
            return "orange_concrete"                          # brand stripe along the whole hull
        if z in (-6, -10) and y % 4 in (1, 2) and -75 < y < 80:
            return "ochre_froglight" if h < 720 else "black_concrete"   # cabin windows
        if y % 9 == 0:
            return "light_gray_concrete"
        return "white_concrete"
    if role == "tower":
        if top:
            return "light_gray_concrete" if y % 6 == 0 else "white_concrete"
        if z in (7, 8):
            if ny > 0.5:
                return "light_blue_stained_glass"             # bridge windows facing the deck
            if abs(nx) > 0.4 and y % 3 != 0:
                return "ochre_froglight"
        if z == 3 and not bottom:
            return "orange_concrete"
        if bottom:
            return "gray_concrete"
        return "white_concrete"
    if role == "fin":
        if z >= d["zmax"] - 1 and y <= -80:
            return "ochre_froglight"                          # beacon on the tip
        if z >= d["top"].get(y, 999):
            return "orange_concrete"
        if z == 14:
            return "light_gray_concrete"
        return "white_concrete"
    if role == "wing":
        if nz > 0.3:
            letter = wing_text(x, y)
            if letter:
                return letter
        if y >= d["front"].get(x, 999) - 1:
            return "orange_concrete"                          # leading edge
        if y <= d["back"].get(x, -999):
            return "gray_concrete"
        if nz < -0.3:
            return "gray_concrete" if x % 10 == 0 else "light_gray_concrete"
        if x % 10 == 0:
            return "light_gray_concrete"
        return "white_concrete"
    if role in ("engine", "nacelle"):
        cx, cz = [float(v) for v in d["obj"]["mc_nozzle"][:2]]
        if role == "nacelle" and y >= d["ymax"] - 1:
            return "verdant_froglight" if d["obj"].get("mc_navlight") == "green" else "magma_block"
        if y <= d["ymin"] + 1:
            return "polished_blackstone"                      # nozzle rim
        if y == d["ymin"] + 7:
            return "orange_concrete"
        ang = round(math.degrees(math.atan2(z - cz, x - cx)))
        if ang % 45 <= 4:
            return "gray_concrete"
        return "light_gray_concrete"
    if role == "cannon":
        return "polished_blackstone"
    if role == "sponson":
        if y >= d["ymax"] - 1:
            return "orange_concrete"
        if z == -9 and abs(nx) > 0.5 and y % 3 != 0:
            return "ochre_froglight" if h < 800 else "black_concrete"
        return "gray_concrete" if bottom else "light_gray_concrete"
    if role == "keel":
        if bottom and x == 0 and y % 5 == 0:
            return "sea_lantern"                              # landing lights
        return "polished_deepslate" if y % 8 == 0 else "gray_concrete"
    if role == "collar":
        if top:
            return "polished_blackstone"
        if math.hypot(x, y) >= 27.5 and not bottom:
            ang = round(math.degrees(math.atan2(y, x)))
            return "ochre_froglight" if ang % 12 <= 3 else "orange_concrete"
        return "gray_concrete"
    if role == "glass":
        return "light_blue_stained_glass"
    if role == "mast":
        return "end_rod"
    return "white_concrete"


def paint_all(info, occ, owner, glow):
    shell, n = shell_and_normals(occ)
    extents(info)
    blocks = {}
    for i, j, k in np.argwhere(shell):
        x, y, z = int(i) + X0, int(j) + Y0, int(k) + Z0
        o = int(owner[i, j, k])
        if o < 0:
            continue
        v = n[:, i, j, k]
        ln = float(np.linalg.norm(v)) or 1.0
        nx, ny, nz = (float(c) / ln for c in v)
        blocks[(x, y, z)] = glow.get((x, y, z)) or paint(info[o], x, y, z, nx, ny, nz)
        if blocks[(x, y, z)] == "light_blue_stained_glass":
            # a dark inner layer behind glass, so it doesn't look into an empty hull
            bx, by, bz = x - round(nx), y - round(ny), z - round(nz)
            if X0 <= bx <= X1 and Y0 <= by <= Y1 and Z0 <= bz <= Z1 and occ[bx - X0, by - Y0, bz - Z0] \
                    and not shell[bx - X0, by - Y0, bz - Z0]:
                blocks[(bx, by, bz)] = "sea_lantern" if jhash(bx, bz, -by) % 9 == 0 else "black_concrete"
    for pos, b in glow.items():                           # glowing nozzle floors are always visible
        blocks.setdefault(pos, b)
    return blocks


# ------------------------------------------------------------------ export

def sky_blocks(blocks):
    """The whole ship for the sky over the survival city: hull + the deck replica with its glass
    dome and a static reactor core. The portals become coloured glass (real nether portal blocks in
    the overworld would lead to the Nether)."""
    out = dict(blocks)
    portal_glass = {(0, -18): "orange_stained_glass", (18, 0): "cyan_stained_glass",
                    (0, 18): "purple_stained_glass", (-18, 0): "lime_stained_glass"}
    for (x, y, z), b in deck_blocks().items():
        if b == "nether_portal":
            mz = -y
            key = min(portal_glass, key=lambda c: (c[0] - x) ** 2 + (c[1] - mz) ** 2)
            b = portal_glass[key]
        out[(x, y, z)] = b
    for dx in range(-2, 3):                                 # the reactor core hanging under the dome
        for dy in range(-2, 3):
            for dz in range(-2, 3):
                inner = max(abs(dx), abs(dy), abs(dz)) <= 1
                out[(dx, dz, 14 + dy)] = "shroomlight" if inner else "orange_stained_glass"
    return out


def export(blocks, path=OUT):
    """Runs along Minecraft +x. Line: x dy z length paletteIndex (dy relative to the deck floor)."""
    palette = sorted(set(blocks.values()))
    index = {b: i for i, b in enumerate(palette)}
    mc = sorted(((-y, z, x), b) for (x, y, z), b in blocks.items())   # (mz, dy, mx)
    runs = []
    for (mz, dy, mx), b in mc:
        if runs and runs[-1][1] == dy and runs[-1][2] == mz and runs[-1][0] + runs[-1][3] == mx \
                and runs[-1][4] == index[b]:
            runs[-1][3] += 1
        else:
            runs.append([mx, dy, mz, 1, index[b]])
    os.makedirs(os.path.dirname(path), exist_ok=True)
    xs = [r[0] for r in runs] + [r[0] + r[3] - 1 for r in runs]
    with gzip.open(path, "wt", encoding="utf-8", newline="\n") as f:
        f.write("NEYRO_SHIP 1\n")
        f.write("bounds %d %d %d %d %d %d\n" % (min(xs), min(r[1] for r in runs), min(r[2] for r in runs),
                                                max(xs), max(r[1] for r in runs), max(r[2] for r in runs)))
        f.write("palette %d\n" % len(palette))
        for b in palette:
            f.write(b + "\n")
        f.write("runs %d\n" % len(runs))
        for r in runs:
            f.write("%d %d %d %d %d\n" % tuple(r))
    counts = {}
    for b in blocks.values():
        counts[b] = counts.get(b, 0) + 1
    print("Exported %d blocks in %d runs, %d block types -> %s (%d KB)"
          % (len(blocks), len(runs), len(palette), os.path.normpath(path), os.path.getsize(path) // 1024))
    for b, c in sorted(counts.items(), key=lambda t: -t[1]):
        print("   %6d  %s" % (c, b))


# ------------------------------------------------------------------ preview of the deck (as the plugin builds it)

def deck_blocks():
    """A copy of NeyroHub.buildHub for the pictures (x, Y=-z, Z=dy)."""
    out = {}
    R = 24

    def put(x, mz, dy, b):
        out[(x, -mz, dy)] = b

    def floor_mat(x, z, r):
        if r < 3.5:
            return "black_concrete"
        if r < 4.5 or 11.5 <= r < 12.5:
            return "orange_concrete"
        if 12.5 <= r < 13.2:
            return "gray_concrete"
        if r > R - 1.5:
            return "polished_blackstone_bricks"
        if x == 0 or z == 0 or (abs(abs(x) - abs(z)) == 0 and r < 12):
            return "shroomlight"
        hh = jhash(x, 100, z)
        return "gilded_blackstone" if hh < 60 else "polished_blackstone" if hh < 500 else \
            "blackstone" if hh < 800 else "polished_deepslate"

    for x in range(-R - 1, R + 2):
        for z in range(-R - 1, R + 2):
            r = math.sqrt(x * x + z * z)
            if r > R + 0.5:
                continue
            put(x, z, 0, floor_mat(x, z, r))
            if r > R - 0.6:
                ang = round(math.degrees(math.atan2(z, x)))
                for y in range(1, 7):
                    m = "white_concrete"
                    if 2 <= y <= 4:
                        m = "light_gray_concrete" if ang % 15 <= 1 else "glass"
                    if y == 6 and ang % 30 <= 1:
                        m = "ochre_froglight"
                    put(x, z, y, m)
            for y in range(7, int(6 + R * 0.62 + 1) + 1):
                dy = (y - 6) / 0.62
                dd = math.sqrt(x * x + z * z + dy * dy)
                if dd < R - 0.55 or dd >= R + 0.45:
                    continue
                ang = round(math.degrees(math.atan2(z, x)))
                put(x, z, y, "light_gray_concrete" if ang % 30 <= 1 or y % 4 == 0 else "glass")
    put(0, 0, 0, "ochre_froglight")
    for sx, sz in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        for a in (-1, 0, 1):
            for b in (-1, 0, 1):
                put(sx * 9 + a, sz * 9 + b, 0, "orange_concrete")
        put(sx * 9, sz * 9, 0, "shroomlight")
        put(sx * 9, sz * 9, 1, "end_rod")
    portals = [(0, -18, "X", 7, 10, "orange_concrete", "ochre_froglight"),
               (18, 0, "Z", 5, 8, "cyan_concrete", "sea_lantern"),
               (0, 18, "X", 5, 8, "purple_concrete", "pearlescent_froglight"),
               (-18, 0, "Z", 5, 8, "lime_concrete", "verdant_froglight")]
    for cx, cz, axis, w, hgt, frame, corner in portals:
        half = w // 2
        for a in range(-half - 1, half + 2):
            for hh in range(0, hgt + 2):
                x, z = (cx + a, cz) if axis == "X" else (cx, cz + a)
                ew, eh = abs(a) == half + 1, hh in (0, hgt + 1)
                put(x, z, hh, corner if ew and eh else frame if ew or eh else "nether_portal")
        for side in (-1, 1):
            off = (half + 3) * side
            x, z = (cx + off, cz) if axis == "X" else (cx, cz + off)
            for y in range(1, hgt):
                put(x, z, y, "polished_blackstone_bricks")
            put(x, z, hgt, corner)
            put(x, z, hgt + 1, "end_rod")
    return out


# ------------------------------------------------------------------ preview pictures

SRGB = {
    "white_concrete": "cfd5d6", "light_gray_concrete": "8a8a82", "gray_concrete": "45484d",
    "black_concrete": "0d0f14", "orange_concrete": "f06a0a", "ochre_froglight": "ffeeb0",
    "shroomlight": "ffaa55", "sea_lantern": "d8fff8", "polished_blackstone": "3a343f",
    "polished_blackstone_bricks": "302a31", "light_blue_stained_glass": "5aa0f0",
    "verdant_froglight": "9cff8a", "magma_block": "ff4a14", "pearlescent_froglight": "f6d0f0",
    "end_rod": "fffbe8", "polished_deepslate": "4a4a4c", "glass": "d0e8f0", "nether_portal": "8a2ee6",
    "cyan_concrete": "15828f", "purple_concrete": "6a24a6", "lime_concrete": "62b41a",
    "gilded_blackstone": "4a3a2a", "blackstone": "2c282e", "light_blue_concrete": "2a8fd0",
}


def lin(hexstr):
    c = [int(hexstr[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in c] + [1.0]


FACES = {
    (1, 0, 0): [(.5, -.5, -.5), (.5, .5, -.5), (.5, .5, .5), (.5, -.5, .5)],
    (-1, 0, 0): [(-.5, -.5, -.5), (-.5, -.5, .5), (-.5, .5, .5), (-.5, .5, -.5)],
    (0, 1, 0): [(-.5, .5, -.5), (-.5, .5, .5), (.5, .5, .5), (.5, .5, -.5)],
    (0, -1, 0): [(-.5, -.5, -.5), (.5, -.5, -.5), (.5, -.5, .5), (-.5, -.5, .5)],
    (0, 0, 1): [(-.5, -.5, .5), (.5, -.5, .5), (.5, .5, .5), (-.5, .5, .5)],
    (0, 0, -1): [(-.5, -.5, -.5), (-.5, .5, -.5), (.5, .5, -.5), (.5, -.5, -.5)],
}


def preview_mesh(blocks, name):
    names = sorted(set(blocks.values()))
    idx = {b: i + 1 for i, b in enumerate(names)}
    grid = np.zeros((NX + 2, NY + 2, NZ + 2), np.int16)
    for (x, y, z), b in blocks.items():
        grid[x - X0 + 1, y - Y0 + 1, z - Z0 + 1] = idx[b]
    solid = grid > 0
    verts, cols = [], []
    colour = np.array([[0, 0, 0, 1]] + [lin(SRGB.get(b, "ff00ff")) for b in names], np.float32)
    for (dx, dy, dz), corners in FACES.items():
        nb = np.roll(solid, (-dx, -dy, -dz), axis=(0, 1, 2))
        cells = np.argwhere(solid & ~nb)
        if not len(cells):
            continue
        centre = cells.astype(np.float32) + np.array([X0 - 1, Y0 - 1, Z0 - 1], np.float32)
        verts.append(centre[:, None, :] + np.array(corners, np.float32)[None, :, :])
        c = colour[grid[cells[:, 0], cells[:, 1], cells[:, 2]]]
        cols.append(np.repeat(c[:, None, :], 4, axis=1))
    v = np.concatenate(verts).reshape(-1, 3)
    c = np.concatenate(cols).reshape(-1, 4)
    nf = len(v) // 4
    me = bpy.data.meshes.new(name)
    me.vertices.add(len(v))
    me.vertices.foreach_set("co", v.ravel())
    me.loops.add(len(v))
    me.loops.foreach_set("vertex_index", np.arange(len(v), dtype=np.int32))
    me.polygons.add(nf)
    me.polygons.foreach_set("loop_start", np.arange(0, len(v), 4, dtype=np.int32))
    me.update(calc_edges=True)
    attr = me.color_attributes.new("Col", "FLOAT_COLOR", "CORNER")
    attr.data.foreach_set("color", c.ravel())
    me.color_attributes.active_color = attr
    try:
        me.color_attributes.render_color_index = me.color_attributes.active_color_index
    except AttributeError:
        pass
    obj = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(obj)
    print("Preview mesh: %d faces" % nf)
    return obj


VIEWS = {
    "1_hero": ((-170, 150, 95), (0, -8, -4), 35, False),
    "2_aft": ((110, -215, 48), (0, -55, 0), 35, False),
    "3_top": ((0, -4, 400), (0, -4, 0), 0, True),
    "4_side": ((270, -6, 14), (0, -6, 2), 35, False),
    "5_deck_forward": ((-6, -16, 7.5), (2, 60, -1), 22, False),
    "6_deck_back": ((4, 15, 4.5), (-2, -70, 10), 22, False),
    "7_deck_side": ((-14, 4, 3.6), (70, -30, 3), 24, False),
}


def render(name, cam_pos, target, lens, ortho, color_type):
    sc = bpy.context.scene
    cam = sc.camera
    cam.location = cam_pos
    cam.rotation_euler = (Vector(target) - Vector(cam_pos)).to_track_quat("-Z", "Y").to_euler()
    sc.render.resolution_y = 1600 if ortho else 900
    if ortho:
        cam.data.type = "ORTHO"
        cam.data.ortho_scale = 235
        cam.rotation_euler = (0, 0, 0)
    else:
        cam.data.type = "PERSP"
        cam.data.lens = lens
    sh = sc.display.shading
    sh.color_type = color_type
    sc.render.filepath = os.path.join(RENDERS, name + ".png")
    bpy.ops.render.render(write_still=True)
    print("Rendered", sc.render.filepath)


def setup_render():
    sc = bpy.context.scene
    sc.render.engine = "BLENDER_WORKBENCH"
    sc.render.resolution_x, sc.render.resolution_y = 1600, 900
    sc.render.resolution_percentage = 100
    sh = sc.display.shading
    # attribute names differ a little between Blender versions
    for attr, value in (("light", "STUDIO"), ("show_shadows", True), ("show_shadow", True),
                        ("shadow_intensity", 0.35), ("show_cavity", True), ("cavity_type", "BOTH"),
                        ("show_specular_highlight", True)):
        if hasattr(sh, attr):
            setattr(sh, attr, value)
    if sc.world is None:
        sc.world = bpy.data.worlds.new("Space")
    sc.world.color = (0.004, 0.006, 0.018)
    sc.view_settings.view_transform = "Standard"
    os.makedirs(RENDERS, exist_ok=True)


def main():
    t0 = time.time()
    parts, info, occ, owner, glow = voxelize()
    blocks = paint_all(info, occ, owner, glow)
    export(blocks)
    export(sky_blocks(blocks), OUT_SKY)
    print("Voxelized in %.1f s" % (time.time() - t0))
    if "--no-render" in ARGS:
        return
    setup_render()
    # the smooth Blender model
    render("0_blender_model", *VIEWS["1_hero"], "MATERIAL")
    for o in parts:
        o.hide_render = True
    # the block version, with the deck as the plugin builds it (deck glass left out to see inside)
    preview = dict(blocks)
    for pos, b in deck_blocks().items():
        if b != "glass":
            preview[pos] = b
    preview_mesh(preview, "NEYRO-1 blocks")
    for name, (pos, tgt, lens, ortho) in VIEWS.items():
        render(name, pos, tgt, lens, ortho, "VERTEX")


main()
