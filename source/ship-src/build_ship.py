"""
NEYRO-1, the main ship of the NeyroCraft hub, modelled in Blender.

Run:  blender --background --python build_ship.py
Saves neyro1.blend next to this script. Open it in Blender to look at or change the ship,
then run voxelize.py to turn it into Minecraft blocks.

Coordinates: 1 Blender unit = 1 block.
    Blender X = Minecraft x            (east)
    Blender Y = -Minecraft z           (+Y is north: the nose points north, where the survival portal is)
    Blender Z = Minecraft y - 100      (Z = 0 is the deck floor, FLOOR_Y = 100)
The origin is the centre block of the deck (the spawn point).

Every part has custom properties the voxelizer reads:
    mc_role      how the part is painted in blocks (hull, wing, engine, glass, ...)
    mc_priority  which part wins where parts overlap
    mc_nozzle    [cx, cz, inner_radius, depth]: hollow glowing exhaust at the back (-Y) end
    mc_ignore    the part is only a guide (the deck is built by the plugin itself)
"""
import math
import os

import bpy
import bmesh

HERE = os.path.dirname(os.path.abspath(__file__))

ROLE_COLOURS = {
    "hull": (0.92, 0.92, 0.94, 1),
    "tower": (0.95, 0.95, 0.96, 1),
    "wing": (0.88, 0.88, 0.90, 1),
    "fin": (0.90, 0.90, 0.92, 1),
    "engine": (0.55, 0.57, 0.62, 1),
    "nacelle": (0.72, 0.73, 0.78, 1),
    "sponson": (0.70, 0.72, 0.76, 1),
    "keel": (0.33, 0.34, 0.38, 1),
    "collar": (1.00, 0.50, 0.05, 1),
    "glass": (0.30, 0.62, 0.95, 1),
    "mast": (0.85, 0.85, 0.80, 1),
    "cannon": (0.20, 0.20, 0.22, 1),
    "deck": (0.55, 0.80, 1.00, 1),
}
PRIORITY = {"hull": 1, "wing": 2, "keel": 3, "sponson": 3, "collar": 4, "tower": 5, "fin": 6,
            "nacelle": 7, "engine": 8, "cannon": 8, "glass": 9, "mast": 9}


# ------------------------------------------------------------------ geometry helpers

def ring(axis, pos, c1, c2, a, b_pos, b_neg=None, n=2.0, count=64):
    """A superellipse ring perpendicular to `axis` at `pos`.
    First in-plane coordinate: centre c1, half size a. Second: centre c2, half size b_pos up / b_neg down."""
    if b_neg is None:
        b_neg = b_pos
    pts = []
    for i in range(count):
        t = 2 * math.pi * i / count
        c, s = math.cos(t), math.sin(t)
        u = c1 + a * math.copysign(abs(c) ** (2 / n), c)
        v = c2 + (b_pos if s >= 0 else b_neg) * math.copysign(abs(s) ** (2 / n), s)
        if axis == "Y":
            pts.append((u, pos, v))
        elif axis == "X":
            pts.append((pos, u, v))
        else:  # "Z"
            pts.append((u, v, pos))
    return pts


def loft(name, rings, role, smooth=True, **props):
    """Closed mesh through the rings (all with the same number of points), capped at both ends."""
    bm = bmesh.new()
    vrings = [[bm.verts.new(p) for p in r] for r in rings]
    count = len(rings[0])
    for a, b in zip(vrings, vrings[1:]):
        for i in range(count):
            j = (i + 1) % count
            bm.faces.new((a[i], a[j], b[j], b[i]))
    bm.faces.new(list(reversed(vrings[0])))
    bm.faces.new(vrings[-1])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return finish(name, bm, role, smooth, **props)


def prism_x(name, poly_yz, x0, x1, role, **props):
    """A plate: polygon in the YZ plane, extruded from x0 to x1."""
    bm = bmesh.new()
    a = [bm.verts.new((x0, y, z)) for y, z in poly_yz]
    b = [bm.verts.new((x1, y, z)) for y, z in poly_yz]
    k = len(poly_yz)
    for i in range(k):
        j = (i + 1) % k
        bm.faces.new((a[i], a[j], b[j], b[i]))
    bm.faces.new(list(reversed(a)))
    bm.faces.new(b)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return finish(name, bm, role, False, **props)


def box(name, x0, y0, z0, x1, y1, z1, role, **props):
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    for v in bm.verts:
        v.co.x = x0 + (v.co.x + 0.5) * (x1 - x0)
        v.co.y = y0 + (v.co.y + 0.5) * (y1 - y0)
        v.co.z = z0 + (v.co.z + 0.5) * (z1 - z0)
    return finish(name, bm, role, False, **props)


def material(role):
    m = bpy.data.materials.get("mc_" + role)
    if m is None:
        m = bpy.data.materials.new("mc_" + role)
        m.diffuse_color = ROLE_COLOURS[role]
        m.use_nodes = True
        bsdf = m.node_tree.nodes.get("Principled BSDF")
        if bsdf is not None:
            bsdf.inputs["Base Color"].default_value = ROLE_COLOURS[role]
            if role == "glass":
                bsdf.inputs["Roughness"].default_value = 0.05
            if role in ("collar",):
                try:
                    bsdf.inputs["Emission Color"].default_value = ROLE_COLOURS[role]
                    bsdf.inputs["Emission Strength"].default_value = 2.0
                except KeyError:
                    pass
    return m


def finish(name, bm, role, smooth, **props):
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    if smooth:
        for p in mesh.polygons:
            p.use_smooth = True
    obj = bpy.data.objects.new(name, mesh)
    COLL.objects.link(obj)
    obj.data.materials.append(material(role))
    obj["mc_role"] = role
    obj["mc_priority"] = PRIORITY.get(role, 1)
    for k, v in props.items():
        obj[k] = v
    return obj


def mirror_x(pts):
    return [(-x, y, z) for x, y, z in pts]


# ------------------------------------------------------------------ the ship

def build():
    # ---- main hull: a long flattened wedge; its top is flush with the deck floor around the dome
    hull_sections = [
        # Y,    zc,   half-width, top, bottom, roundness
        (-86, -4.0, 22, 7.0, 8.0, 3.0),
        (-80, -3.5, 30, 10.5, 11.0, 3.2),
        (-64, -3.0, 34, 11.0, 13.0, 3.2),
        (-46, -3.5, 35, 7.0, 13.5, 3.0),
        (-32, -4.0, 34, 3.4, 14.0, 3.0),
        (-10, -4.0, 33, 3.4, 14.5, 3.0),
        (14, -4.0, 31, 3.4, 14.0, 3.0),
        (34, -4.2, 25, 3.6, 12.0, 2.8),
        (56, -4.6, 17, 3.8, 9.0, 2.6),
        (78, -5.0, 9.5, 3.0, 6.0, 2.4),
        (96, -5.6, 3.2, 1.5, 2.6, 2.2),
        (104, -6.0, 0.6, 0.4, 0.6, 2.0),
    ]
    loft("Hull", [ring("Y", y, 0, zc, w, t, b, n) for y, zc, w, t, b, n in hull_sections], "hull")

    # ---- the round base under the dome, with a glowing rim
    loft("Deck collar", [ring("Z", -4.5, 0, 0, 26, 26), ring("Z", -2.5, 0, 0, 29, 29),
                         ring("Z", -0.6, 0, 0, 29, 29)], "collar")

    # ---- bridge tower behind the deck
    tower = [
        (-34, 0.5, 5, 0.5, 1.5, 2.5),
        (-42, 4.0, 10, 4.5, 5.0, 3.0),
        (-52, 5.0, 12, 6.5, 6.0, 3.2),
        (-68, 5.0, 12, 6.5, 6.0, 3.2),
        (-76, 4.0, 9, 4.5, 5.0, 3.0),
        (-80, 3.0, 5, 2.0, 3.0, 2.5),
    ]
    loft("Bridge tower", [ring("Y", y, 0, zc, w, t, b, n) for y, zc, w, t, b, n in tower], "tower")

    # ---- dorsal fin and antenna masts
    prism_x("Dorsal fin", [(-54, 10), (-78, 10), (-90, 27), (-82, 28)], -1.3, 1.3, "fin")
    for x in (-7, 7):
        box("Antenna %+d" % x, x - 0.4, -60.4, 10, x + 0.4, -59.6, 17.4, "mast")

    # ---- cockpit canopy on the nose
    canopy = [
        (62, -1.5, 0.5, 0.3, 0.3, 2.0),
        (68, -1.0, 4.5, 2.2, 2.0, 2.4),
        (80, -2.0, 5.0, 2.5, 2.0, 2.4),
        (88, -3.5, 2.5, 1.2, 1.5, 2.2),
        (91, -4.5, 0.5, 0.3, 0.3, 2.0),
    ]
    loft("Cockpit canopy", [ring("Y", y, 0, zc, w, t, b, n) for y, zc, w, t, b, n in canopy], "glass")

    # ---- keel under the hull
    keel = [
        (48, -12, 1.5, 2.0, 1.0, 2.0),
        (34, -14, 5.0, 4.0, 5.0, 2.5),
        (-46, -14, 6.5, 4.0, 6.0, 2.5),
        (-66, -12, 4.0, 3.0, 2.0, 2.2),
    ]
    loft("Keel", [ring("Y", y, 0, zc, w, t, b, n) for y, zc, w, t, b, n in keel], "keel")

    # ---- three main engines
    for name, cx, cz, secs in (
        ("Main engine", 0, -3.0, [(-72, 8.5), (-90, 9.0), (-97, 9.8)]),
        ("Engine L", -21, -6.5, [(-70, 6.4), (-87, 6.7), (-93, 7.3)]),
        ("Engine R", 21, -6.5, [(-70, 6.4), (-87, 6.7), (-93, 7.3)]),
    ):
        r_back = secs[-1][1]
        loft(name, [ring("Y", y, cx, cz, r, r) for y, r in secs], "engine",
             mc_nozzle=[cx, cz, r_back - 1.8, 3])

    # ---- side pods with rows of windows
    for side in (-1, 1):
        pods = [(34, 0.7), (26, 4.6), (-38, 5.0), (-48, 3.5), (-50, 0.8)]
        loft("Side pod " + ("L" if side < 0 else "R"),
             [ring("Y", y, side * 32, -9, r, r) for y, r in pods], "sponson")

    # ---- swept wings with a slight upward angle, so they are seen from the deck windows
    wing = [
        # X,  centre Y, half-chord, centre Z, half-thickness
        (22, -18, 38, -2.5, 2.2),
        (50, -40, 22, 1.0, 1.8),
        (80, -58, 10, 4.5, 1.2),
        (83, -59, 7, 4.8, 0.8),
    ]
    for side in (-1, 1):
        rings = [ring("X", side * x, yc, zc, a, b, n=2.6) for x, yc, a, zc, b in wing]
        loft("Wing " + ("L" if side < 0 else "R"), rings, "wing", smooth=False)

        nac = [(-26, 0.6), (-32, 3.2), (-40, 4.3), (-66, 4.3), (-72, 4.8), (-73, 4.8)]
        loft("Wing engine " + ("L" if side < 0 else "R"),
             [ring("Y", y, side * 84, 5, r, r) for y, r in nac], "nacelle",
             mc_nozzle=[side * 84, 5, 3.0, 2], mc_navlight="green" if side > 0 else "red")
        loft("Wing cannon " + ("L" if side < 0 else "R"),
             [ring("Y", y, side * 84, 5, 0.9, 0.9, count=16) for y in (-8, -27)], "cannon")

    # ---- guide only: the deck the plugin builds (floor, wall with windows, glass dome, portals)
    deck = [ring("Z", z, 0.0, 0.0, r, r) for z, r in ((0, 24.5), (6, 24.5))]
    dome_steps = 10
    for i in range(1, dome_steps + 1):
        a = (math.pi / 2) * i / dome_steps
        r = max(0.3, 24.5 * math.cos(a))
        deck.append(ring("Z", 6 + 24.5 * 0.62 * math.sin(a), 0.0, 0.0, r, r))
    d = loft("DECK (built by the plugin)", deck, "deck", mc_ignore=True)
    d.display_type = "WIRE"
    d.hide_render = True


def scene_setup():
    sc = bpy.context.scene
    sc.unit_settings.system = "METRIC"
    world = bpy.data.worlds.new("Space")
    world.color = (0.005, 0.007, 0.02)
    sc.world = world
    cam_data = bpy.data.cameras.new("Camera")
    cam_data.lens = 35
    cam_data.clip_end = 2000
    cam = bpy.data.objects.new("Camera", cam_data)
    sc.collection.objects.link(cam)
    cam.location = (-165, 150, 95)
    look_at(cam, (0, -5, -4))
    sc.camera = cam
    sun_data = bpy.data.lights.new("Star", "SUN")
    sun_data.energy = 3.0
    sun = bpy.data.objects.new("Star", sun_data)
    sun.rotation_euler = (math.radians(50), 0, math.radians(-35))
    sc.collection.objects.link(sun)


def look_at(obj, target):
    from mathutils import Vector
    direction = Vector(target) - obj.location
    obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


if __name__ == "__main__":
    bpy.ops.wm.read_factory_settings(use_empty=True)
    COLL = bpy.data.collections.new("NEYRO-1")
    bpy.context.scene.collection.children.link(COLL)
    build()
    scene_setup()
    out = os.path.join(HERE, "neyro1.blend")
    bpy.ops.wm.save_as_mainfile(filepath=out)
    print("NEYRO-1 saved:", out, "parts:", len(COLL.objects))
