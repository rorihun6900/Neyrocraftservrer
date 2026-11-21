import bpy,json
from pathlib import Path
from mathutils import Vector
root=Path(__file__).resolve().parent
pack=root.parent/'retropack-src'/'saberpack'/'assets'/'neyrocraft'
for d in ('models/item','items','textures/item','equipment','textures/entity/equipment/wings'): (pack/d).mkdir(parents=True,exist_ok=True)
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
def mat(name,rgb):
 m=bpy.data.materials.new(name);m.diffuse_color=(*rgb,1);m.use_nodes=True;n=m.node_tree.nodes;n.clear();o=n.new('ShaderNodeOutputMaterial');e=n.new('ShaderNodeEmission');e.inputs['Color'].default_value=(*rgb,1);e.inputs['Strength'].default_value=1.2;m.node_tree.links.new(e.outputs[0],o.inputs['Surface']);return m
mats={'metal':mat('titanium',(.28,.40,.48)),'dark':mat('black ceramic',(.025,.05,.09)),'cyan':mat('cyan power cell',(.08,.82,1)),'orange':mat('orange exhaust', (1,.32,.05)),'white':mat('white blade',(0.8,1,1))}
for key,rgb in {'metal':(80,110,125,255),'dark':(12,23,34,255),'cyan':(40,215,255,255),'orange':(255,105,20,255),'white':(220,255,255,255)}.items():
 im=bpy.data.images.new('gear_'+key,1,1,alpha=True);im.pixels=[x/255 for x in rgb];im.filepath_raw=str(pack/'textures/item'/('gear_'+key+'.png'));im.file_format='PNG';im.save()
def cube(name,lo,hi,material,group):
 mid=[(a+b)/2 for a,b in zip(lo,hi)];half=[(b-a)/2 for a,b in zip(lo,hi)];bpy.ops.mesh.primitive_cube_add(location=mid);o=bpy.context.object;o.name=name;o.scale=half;o.data.materials.append(mats[material]);o['material_key']=material;o['group']=group;o.modifiers.new('soft bevel','BEVEL').width=.15;o.modifiers.new('weighted normals','WEIGHTED_NORMAL');return {'from':lo,'to':hi,'light_emission':15 if material in ('cyan','orange','white') else 0,'faces':{f:{'texture':'#'+material} for f in ('north','south','east','west','up','down')}}
jet=[]
def j(n,a,b,m):jet.append(cube(n,a,b,m,'jetpack'))
j('backplate',(3,3,2),(13,13,5),'dark');j('spine',(6.5,3,4),(9.5,14,7),'metal');j('reactor',(5,7,7),(11,11,8.5),'cyan')
for x in (2,11):
 j('twin engine',(x,2,4),(x+3,12,9),'metal');j('engine core',(x+.5,3,8.8),(x+2.5,11,10),'dark');j('exhaust',(x+.3,0,4.5),(x+2.7,2,8.5),'orange');j('nozzle',(x-.3,-1,4),(x+3.3,0.5,9),'dark');j('thruster glow',(x+.4,-1.8,5),(x+2.6,-.8,8),'cyan')
j('left strap',(1,5,1),(3,13,3),'dark');j('right strap',(13,5,1),(15,13,3),'dark');j('corp crest',(7,11,8.7),(9,13,9.2),'orange')
j('left wing',(-5,3,3),(3,12,4),'metal');j('right wing',(13,3,3),(21,12,4),'metal')
j('left wing light',(-5,4,4),(-4,11,4.5),'cyan');j('right wing light',(20,4,4),(21,11,4.5),'cyan')
saber=[]
def s(n,a,b,m):saber.append(cube(n,a,b,m,'saber'))
s('pommel',(6,-2,6),(10,0,10),'dark');s('grip',(6.7,0,6.7),(9.3,6,9.3),'metal')
for y in (1,3,5):s('grip ring',(6.3,y,6.3),(9.7,y+.4,9.7),'dark')
s('guard',(5.5,6,5.5),(10.5,7.2,10.5),'orange');s('emitter',(7,7,7),(9,9,9),'dark');s('blade aura',(6.9,9,6.9),(9.1,31,9.1),'cyan');s('blade core',(7.5,9,7.5),(8.5,31.2,8.5),'white');s('tip',(7.2,31,7.2),(8.8,32.5,8.8),'white')
textures={k:'neyrocraft:item/gear_'+k for k in mats}
def write(name,elements,display):
 (pack/'models/item'/f'{name}.json').write_text(json.dumps({'textures':textures,'elements':elements,'display':display},ensure_ascii=False,separators=(',',':')),encoding='utf-8')
 (pack/'items'/f'{name}.json').write_text(json.dumps({'model':{'type':'minecraft:model','model':'neyrocraft:item/'+name}},separators=(',',':')),encoding='utf-8')
write('admin_jetpack',jet,{'gui':{'rotation':[20,225,0],'scale':[.7,.7,.7]},'fixed':{'rotation':[0,180,0]},'ground':{'scale':[.5,.5,.5]}})
# The admin saber now reuses the animated cyan saber from SaberArena.
(pack/'items/admin_saber.json').write_text(json.dumps({'model':{'type':'minecraft:model','model':'neyrocraft:item/saber_cyan_3'}},separators=(',',':')),encoding='utf-8')
vanilla=root/'vanilla-elytra.png'
if vanilla.exists():
 source=bpy.data.images.load(str(vanilla));source.pixels[:]
 wing=bpy.data.images.new('admin_jetpack_wings',64,32,alpha=True)
 out=[]
 for y in range(32):
  for x in range(64):
   i=(y*64+x)*4;r,g,b,a=source.pixels[i:i+4]
   if a<.05:out.extend((0,0,0,0));continue
   light=(r+g+b)/3
   if x in (35,36,44,45) and 4<y<26:out.extend((.10,.85,1,a))
   else:out.extend((.08+light*.25,.20+light*.4,.32+light*.45,a))
 wing.pixels=out;wing.filepath_raw=str(pack/'textures/entity/equipment/wings/admin_jetpack.png');wing.file_format='PNG';wing.save()
(pack/'equipment/admin_jetpack.json').write_text(json.dumps({'layers':{'wings':[{'texture':'neyrocraft:admin_jetpack'}]}},separators=(',',':')),encoding='utf-8')
world=bpy.context.scene.world;world.color=(.02,.025,.04);bpy.ops.object.camera_add(location=(30,-42,27));cam=bpy.context.object;cam.rotation_euler=(Vector((8,7,9))-cam.location).to_track_quat('-Z','Y').to_euler();cam.data.type='ORTHO';cam.data.ortho_scale=30;bpy.context.scene.camera=cam
sc=bpy.context.scene;sc.render.engine='CYCLES';sc.cycles.samples=32;sc.render.resolution_x=512;sc.render.resolution_y=512;sc.render.resolution_percentage=100;sc.render.film_transparent=True
for group,name in [('jetpack','jetpack-preview.png'),('saber','admin-saber-preview.png')]:
 for o in bpy.data.objects:
  if o.type=='MESH':o.hide_render=o.get('group')!=group
 sc.render.filepath=str(root/name);bpy.ops.render.render(write_still=True)
for o in bpy.data.objects: o.hide_render=False
bpy.ops.wm.save_as_mainfile(filepath=str(root/'NeyroGear.blend'))
