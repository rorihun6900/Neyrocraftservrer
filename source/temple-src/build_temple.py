"""Build the NeyroCraft anarchy temple and export its block geometry.

Run with Blender --background --factory-startup --python-exit-code 1 --python build_temple.py
The .blend file contains the assembled geometry of both temples.
"""

import gzip
from pathlib import Path

import bpy
from mathutils import Vector


HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parent / "neyrohub-src" / "resources" / "ship"
X_MIN, X_MAX = -16, 16
Y_MIN, Y_MAX = -3, 19
Z_MIN, Z_MAX = -3, 25

BLOCKS = {
    "air": (0, 0, 0),
    "polished_blackstone": (0.08, 0.07, 0.09),
    "polished_blackstone_bricks": (0.10, 0.09, 0.11),
    "chiseled_polished_blackstone": (0.15, 0.12, 0.16),
    "polished_basalt[axis=y]": (0.13, 0.12, 0.15),
    "polished_deepslate": (0.19, 0.18, 0.21),
    "deepslate_tiles": (0.12, 0.13, 0.17),
    "red_nether_bricks": (0.34, 0.055, 0.09),
    "red_concrete": (0.58, 0.04, 0.06),
    "red_stained_glass": (0.58, 0.10, 0.12),
    "crying_obsidian": (0.21, 0.06, 0.32),
    "shroomlight": (1.0, 0.26, 0.07),
    "redstone_block": (0.75, 0.015, 0.045),
}


def build_plan(floating):
    blocks = {}

    def block(x, y, z, name):
        blocks[x, y, z] = name

    # A solid stepped plinth under the chamber hides the void from doorways.
    for z in range(Z_MIN, Z_MAX + 1):
        for x in range(X_MIN, X_MAX + 1):
            side = abs(x)
            block(x, 0, z, "red_nether_bricks" if side in (3, 10) else
                  "polished_blackstone" if side < 4 else "polished_deepslate")
            for y in range(Y_MIN, 0):
                block(x, y, z, "polished_basalt[axis=y]")

    # The pointed barrel vault meets layered buttresses on both sides.
    for z in range(Z_MIN, 21):
        rib = z in (0, 4, 8, 12, 16, 20)
        for x in range(X_MIN, X_MAX + 1):
            side = abs(x)
            height = 17 - side // 2
            for y in range(1, height + 1):
                if z == Z_MIN or side >= 14 or y >= height - (1 if rib else 0):
                    block(x, y, z, "red_nether_bricks" if rib else "deepslate_tiles")
            if side == 13 and rib:
                for y in range(1, height):
                    block(x, y, z, "polished_basalt[axis=y]" if 2 <= y <= 6
                          else "chiseled_polished_blackstone")
                block(x, 3, z, "shroomlight")
                block(x, 7, z, "red_nether_bricks")

    # Blind lancet windows with lit red glass and a solid backing wall.
    for z in (2, 6, 10, 14, 18):
        for side in (-1, 1):
            for y in range(3, 9):
                for inset in (-1, 0, 1):
                    if y in (3, 8) and inset:
                        continue
                    block(side * 14, y, z + inset, "red_stained_glass")
                    block(side * 15, y, z + inset, "shroomlight")
                    block(side * 16, y, z + inset, "red_nether_bricks")

    # Center aisle lighting and blackstone brazier stands.
    for z in range(2, 20, 3):
        for side in (-1, 1):
            block(side * 3, 0, z, "shroomlight")
            block(side * 6, 1, z, "chiseled_polished_blackstone")
            block(side * 6, 2, z, "red_nether_bricks")
            block(side * 6, 3, z, "shroomlight")

    # Gate plane stays walkable for X=-1..1, Y=1..5. Above it sits a crimson crown.
    for x in range(-7, 8):
        side = abs(x)
        for y in range(1, 12 - side // 2):
            if side < 2 and y < 6:
                continue
            if side > 3 and y < 4 and not (side == 7):
                continue
            block(x, y, 0, "red_nether_bricks" if side <= 3 else "chiseled_polished_blackstone")
            if side in (2, 6) and y in (2, 5, 8):
                block(x, y, 0, "shroomlight")
        if side <= 4:
            block(x, 12 - side // 2, 0, "crying_obsidian")
    for y in range(7, 11):
        for x in range(-1, 2):
            block(x, y, 1, "redstone_block" if x == 0 else "red_concrete")

    # Gate backdrop is separated by a full walkable block so the effect has depth.
    for x in range(-2, 3):
        for y in range(1, 8):
            block(x, y, -2, "red_stained_glass")
            block(x, y, -3, "shroomlight" if y in (2, 5) else "red_nether_bricks")

    # A lower, enclosed vestibule ends either in a blind sanctuary wall (void)
    # or an open threshold with broad steps down towards the anarchy terrain.
    for z in range(21, Z_MAX + 1):
        for x in range(X_MIN, X_MAX + 1):
            side = abs(x)
            for y in range(1, 10):
                inside = side <= 5 and y <= 6
                if inside and (z != Z_MAX or not floating):
                    continue
                block(x, y, z, "red_nether_bricks" if y in (1, 7) else "polished_blackstone_bricks")
        for side in (-1, 1):
            block(side * 6, 4, z, "shroomlight")
    # Close the roof-to-vestibule seam, with just the central doorway open.
    for x in range(X_MIN, X_MAX + 1):
        for y in range(1, 18 - abs(x) // 2):
            if abs(x) <= 5 and y <= 6:
                continue
            block(x, y, 20, "red_nether_bricks" if y in (1, 7, 8) else "deepslate_tiles")

    if floating:
        # A lit rear mural reads as a solid apse from the spawn point.
        for x in range(-3, 4):
            for y in range(2, 7):
                if abs(x) + abs(y - 4) <= 3:
                    block(x, y, Z_MAX, "shroomlight" if x == 0 else "redstone_block")
    else:
        # Ritual hall on the arrival side, with a clear central route to the exit.
        for z in range(10, 20):
            for x in range(-8, 9):
                edge = abs(x) + abs(z - 15)
                if edge in (5, 8):
                    block(x, 0, z, "crying_obsidian" if abs(x) > 2 else "red_nether_bricks")
        for side in (-1, 1):
            for z in (12, 18):
                for y in range(1, 11):
                    block(side * 9, y, z, "polished_basalt[axis=y]" if y not in (1, 10)
                          else "chiseled_polished_blackstone")
                block(side * 9, 7, z, "shroomlight")
            for z in range(13, 18):
                for x in (8, 9, 10):
                    block(side * x, 1, z, "polished_blackstone_bricks")
            for y in range(2, 8):
                block(side * 9, y, 15, "crying_obsidian" if y != 4 else "red_stained_glass")
                block(side * 10, y, 15, "shroomlight" if y == 4 else "red_nether_bricks")
        for x in range(-9, 10):
            height = 13 - abs(x) // 3
            for z in (12, 18):
                block(x, height, z, "red_nether_bricks")
                block(x, height + 1, z, "deepslate_tiles")
                if abs(x) in (0, 4, 8):
                    block(x, height - 1, z, "shroomlight")
    return blocks


def add_geometry(name, plan, origin_x):
    collection = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(collection)
    # Each cell emits its exposed faces. Interior faces are omitted from the model.
    face_masks = (
        ((-1, 0, 0), (0, 4, 7, 3)), ((1, 0, 0), (1, 2, 6, 5)),
        ((0, -1, 0), (0, 1, 5, 4)), ((0, 1, 0), (3, 7, 6, 2)),
        ((0, 0, -1), (0, 3, 2, 1)), ((0, 0, 1), (4, 5, 6, 7)),
    )
    cube = ((0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0),
            (0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1))
    for block_id in BLOCKS:
        vertices, faces = [], []
        for (x, y, z), cell in plan.items():
            if cell != block_id:
                continue
            for (dx, dy, dz), corners in face_masks:
                if (x + dx, y + dy, z + dz) in plan:
                    continue
                face = []
                for corner in corners:
                    ix, iy, iz = cube[corner]
                    face.append(len(vertices))
                    vertices.append((origin_x + x + ix, z + iz, y + iy))
                faces.append(tuple(reversed(face)))
        if not faces:
            continue
        mesh = bpy.data.meshes.new(block_id)
        mesh.from_pydata(vertices, [], faces)
        mesh.update()
        obj = bpy.data.objects.new(block_id, mesh)
        collection.objects.link(obj)
        obj.data.materials.append(bpy.data.materials[block_id])
        obj["minecraft_block"] = "minecraft:" + block_id
    return collection


def export_ship(name, plan, facing):
    indexed = {block: index for index, block in enumerate(BLOCKS)}
    runs = []
    for y in range(Y_MIN, Y_MAX + 1):
        for z in range(Z_MIN, Z_MAX + 1):
            values = [indexed[plan.get((x, y, z), "air")] for x in range(X_MIN, X_MAX + 1)]
            first = 0
            for index in range(1, len(values) + 1):
                if index < len(values) and values[index] == values[first]:
                    continue
                runs.append(f"{first + X_MIN} {y} {facing * z} {index - first} {values[first]}")
                first = index
    zs = sorted((facing * Z_MIN, facing * Z_MAX))
    header = ("NEYRO_SHIP 1\n"
              f"bounds {X_MIN} {Y_MIN} {zs[0]} {X_MAX} {Y_MAX} {zs[1]}\n"
              f"palette {len(BLOCKS)}\n")
    data = header + "\n".join(f"minecraft:{key}" for key in BLOCKS)
    data += f"\nruns {len(runs)}\n" + "\n".join(runs) + "\n"
    (OUTPUT / f"{name}.ship.gz").write_bytes(gzip.compress(data.encode("ascii"), mtime=0))


bpy.ops.wm.read_factory_settings(use_empty=True)
for name, color in BLOCKS.items():
    material = bpy.data.materials.new(name)
    material.diffuse_color = (*color, 1.0)

surface = build_plan(False)
void = build_plan(True)
for plan in (surface, void):
    assert all(plan.get((x, y, 0)) is None for x in range(-1, 2) for y in range(1, 6))
    assert all(plan.get((x, 0, z)) for x in range(-1, 2) for z in (0, 8, 10))
add_geometry("Anarchy · sanctuary and exterior", surface, 0)
add_geometry("Hub 3 · sealed sanctuary", void, 45)
for screen in bpy.data.screens:
    for area in screen.areas:
        if area.type == "VIEW_3D":
            area.spaces.active.shading.color_type = "MATERIAL"
            region = area.spaces.active.region_3d
            region.view_location = (22, 11, 5)
            region.view_rotation = Vector((-42, 58, -43)).to_track_quat("-Z", "Y")
            region.view_distance = 105

export_ship("anarchy_temple", surface, 1)
export_ship("hub3_temple", void, -1)
bpy.context.preferences.filepaths.save_version = 0
bpy.ops.wm.save_as_mainfile(filepath=str(HERE / "anarchy-temples.blend"))
print("Created both block models and anarchy-temples.blend.")
