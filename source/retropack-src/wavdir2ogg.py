import bpy, sys, os
argv = sys.argv[sys.argv.index("--") + 1:]
src_dir, dst_dir = argv[0], argv[1]
os.makedirs(dst_dir, exist_ok=True)
scene = bpy.context.scene
scene.render.fps = 100
scene.sequence_editor_create()
ed = scene.sequence_editor
coll = ed.strips if hasattr(ed, "strips") else ed.sequences
done = 0
for name in sorted(os.listdir(src_dir)):
    if not name.lower().endswith(".wav"):
        continue
    for s in list(coll):
        coll.remove(s)
    strip = coll.new_sound("s", os.path.join(src_dir, name), 1, 1)
    scene.frame_start = 1
    scene.frame_end = max(2, strip.frame_final_end - 1)
    bpy.ops.sound.mixdown(filepath=os.path.join(dst_dir, name[:-4] + ".ogg"), check_existing=False, container='OGG',
                          codec='VORBIS', format='S16', mixrate=44100, accuracy=1024, channels='MONO', bitrate=64)
    done += 1
print("CONVERTED", done)
