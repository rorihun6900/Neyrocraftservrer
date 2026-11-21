import bpy, math
from mathutils import Vector
from math import radians
from pathlib import Path
root=Path(__file__).resolve().parent
bpy.ops.object.select_all(action='SELECT'); bpy.ops.object.delete(use_global=False)
def mat(name,color,alpha=1):
 m=bpy.data.materials.new(name); m.diffuse_color=(*color,alpha); m.use_nodes=True
 n=m.node_tree.nodes; n.clear(); o=n.new('ShaderNodeOutputMaterial'); e=n.new('ShaderNodeEmission'); e.inputs['Color'].default_value=(*color,1); e.inputs['Strength'].default_value=1.25; m.node_tree.links.new(e.outputs[0],o.inputs['Surface']); return m
skin=mat('Ami cyan holographic skin',(.12,.68,.86)); light=mat('Cyan rim glow',(.3,.95,1)); suit=mat('Midnight pilot suit',(.025,.14,.28)); shadow=mat('Deep cobalt',(.015,.06,.15)); iris=mat('Violet irises',(.43,.19,.86)); white=mat('Pale eye glow',(.72,.98,1)); metal=mat('Silver details',(.31,.64,.78))
def uv(name,loc,scale,material):
 bpy.ops.mesh.primitive_uv_sphere_add(segments=24,ring_count=12,location=loc); o=bpy.context.object; o.name=name; o.scale=scale; o.data.materials.append(material); bpy.ops.object.transform_apply(location=False,rotation=False,scale=True); return o
def cyl(name,loc,r,depth,material,r2=None):
 bpy.ops.mesh.primitive_cone_add(vertices=24,radius1=r,radius2=r if r2 is None else r2,depth=depth,location=loc); o=bpy.context.object;o.name=name;o.data.materials.append(material);o.modifiers.new('soft edges','BEVEL').width=.035;o.modifiers.new('weighted normals','WEIGHTED_NORMAL');return o
def tube(name,points,r,material):
 cu=bpy.data.curves.new(name,'CURVE');cu.dimensions='3D';cu.bevel_depth=r;cu.bevel_resolution=3;sp=cu.splines.new('BEZIER');sp.bezier_points.add(len(points)-1)
 for p,co in zip(sp.bezier_points,points):p.co=co;p.handle_left_type='AUTO';p.handle_right_type='AUTO'
 ob=bpy.data.objects.new(name,cu);bpy.context.collection.objects.link(ob);ob.data.materials.append(material);return ob
# Adult Twi'lek scientist: two long lekku, fitted pilot uniform, expressive face.
uv('torso',(0,0,1.31),(.36,.19,.48),suit)
uv('shoulders',(0,0,1.66),(.43,.20,.17),suit)
cyl('waist',(0,0,1.01),.23,.28,suit,r2=.31)
for x in (-.18,.18):
 cyl('leg', (x,0,.50),.105,.78,suit)
 uv('boot',(x,-.045,.16),(.125,.20,.16),shadow)
 tube('boot rim',[(x-.09,-.17,.27),(x,-.19,.28),(x+.09,-.17,.27)],.02,light)
 uv('hip',(x,0,.87),(.15,.15,.16),suit)
 for sign in [1]:
  tube('arm',[(x*2.15,0,1.65),(x*2.5,0,1.34),(x*2.22,-.07,1.06)],.095,skin)
  uv('glove',(x*2.22,-.07,1.05),(.09,.11,.16),shadow)
uv('neck',(0,0,1.85),(.11,.11,.17),skin)
uv('face',(0,-.045,2.12),(.30,.19,.37),skin)
uv('jaw',(0,-.11,1.93),(.20,.15,.13),skin)
# lekku curve from crown over shoulders and down chest
for x in (-.25,.25):
 tube('lekku',[(x,0,2.44),(x*1.4,.05,2.29),(x*1.55,-.02,1.92),(x*1.6,-.1,1.55),(x*1.48,-.13,1.12)],.115,skin)
 tube('lekku luminous stripe',[(x*1.43,-.13,1.9),(x*1.58,-.19,1.65),(x*1.52,-.20,1.35)],.013,light)
 for z in (1.42,1.64,1.86): tube('lekku dark ring',[(x*1.40,-.18,z),(x*1.62,-.13,z)],.025,shadow)
uv('head cap',(0,0,2.46),(.33,.22,.13),shadow)
tube('cap trim',[(-.34,-.10,2.38),(0,-.22,2.39),(.34,-.10,2.38)],.023,light)
for x in (-.13,.13):
 uv('eye white',(x,-.218,2.18),(.092,.018,.044),white)
 uv('iris',(x,-.24,2.18),(.039,.013,.042),iris)
 uv('pupil',(x,-.25,2.18),(.015,.008,.03),shadow)
 tube('eyebrow',[(x-.08,-.215,2.27),(x,-.24,2.28),(x+.08,-.215,2.25)],.012,shadow)
uv('nose',(0,-.235,2.08),(.034,.045,.06),skin)
tube('lips',[(-.075,-.22,1.99),(0,-.25,1.975),(.075,-.22,1.99)],.018,iris)
# elegant high collar and scientist insignia
for x in (-.19,.19): tube('collar',[(x,-.15,1.77),(x*.65,-.18,1.87),(0,-.17,1.83)],.035,metal)
uv('chest emblem',(0,-.205,1.58),(.065,.02,.085),light)
for x in (-.30,.30): tube('shoulder seam',[(x*.7,-.17,1.69),(x,-.17,1.60),(x*1.1,-.13,1.48)],.019,light)
# hologram base and scan rings
cyl('projector',(0,0,.045),.38,.09,metal)
for z,r in ((.10,.36),(.78,.41),(1.48,.50),(2.20,.43)):
 bpy.ops.mesh.primitive_torus_add(major_radius=r,minor_radius=.008,location=(0,0,z));bpy.context.object.name='Hologram scan ring';bpy.context.object.data.materials.append(light)
world=bpy.context.scene.world;world.color=(.01,.02,.04)
bpy.ops.object.camera_add(location=(0,-6,1.55));cam=bpy.context.object;target=Vector((0,0,1.35));cam.rotation_euler=(target-cam.location).to_track_quat('-Z','Y').to_euler();cam.data.type='ORTHO';cam.data.ortho_scale=3.0;bpy.context.scene.camera=cam
sc=bpy.context.scene;sc.render.engine='CYCLES';sc.cycles.samples=32;sc.render.resolution_x=256;sc.render.resolution_y=256;sc.render.resolution_percentage=100;sc.render.film_transparent=True
sc.render.image_settings.file_format='PNG';sc.render.filepath=str(root/'ami_render.png');bpy.ops.wm.save_as_mainfile(filepath=str(root/'ami.blend'));bpy.ops.render.render(write_still=True)
