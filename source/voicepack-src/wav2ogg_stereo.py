"""Stereo WAV -> OGG Vorbis with Blender: blender --background --factory-startup --python wav2ogg_stereo.py -- in.wav out.ogg"""
import sys

import bpy

src, dst = sys.argv[sys.argv.index("--") + 1:][:2]
scene = bpy.context.scene
scene.render.fps = 100
scene.sequence_editor_create()
ed = scene.sequence_editor
coll = ed.strips if hasattr(ed, "strips") else ed.sequences
strip = coll.new_sound("music", src, 1, 1)
scene.frame_start = 1
scene.frame_end = max(2, strip.frame_final_end - 1)
bpy.ops.sound.mixdown(filepath=dst, check_existing=False, container='OGG', codec='VORBIS', format='S16',
                      mixrate=44100, accuracy=1024, channels='STEREO', bitrate=128)
print("CONVERTED", dst)
