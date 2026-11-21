import bpy, sys
argv = sys.argv[sys.argv.index("--") + 1:]
src, dst, channels, bitrate = argv[0], argv[1], argv[2], int(argv[3])
scene = bpy.context.scene
scene.render.fps = 25
scene.sequence_editor_create()
ed = scene.sequence_editor
coll = ed.strips if hasattr(ed, "strips") else ed.sequences
strip = coll.new_sound("snd", src, 1, 1)
scene.frame_start = 1
scene.frame_end = strip.frame_final_end - 1
bpy.ops.sound.mixdown(filepath=dst, check_existing=False, container='OGG', codec='VORBIS',
                      format='S16', mixrate=48000, accuracy=1024, channels=channels, bitrate=bitrate)
print("MIXDOWN_OK", dst, strip.frame_final_end)
