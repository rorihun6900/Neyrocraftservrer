import bpy, sys, os
argv = sys.argv[sys.argv.index("--") + 1:]
frames_dir, audio, out = argv[0], argv[1], argv[2]
files = sorted(f for f in os.listdir(frames_dir) if f.endswith(".jpg"))
scene = bpy.context.scene
scene.render.resolution_x = 1920
scene.render.resolution_y = 1080
scene.render.resolution_percentage = 100
scene.render.fps = 30
scene.frame_start = 1
scene.frame_end = len(files)
scene.sequence_editor_create()
ed = scene.sequence_editor
coll = ed.strips if hasattr(ed, "strips") else ed.sequences
img = coll.new_image("frames", os.path.join(frames_dir, files[0]), 1, 1)
for f in files[1:]:
    img.elements.append(f)
coll.new_sound("music", audio, 2, 1)
r = scene.render
if hasattr(r.image_settings, "media_type"):
    r.image_settings.media_type = 'VIDEO'
r.image_settings.file_format = 'FFMPEG'
r.ffmpeg.format = 'MPEG4'
r.ffmpeg.codec = 'H264'
r.ffmpeg.constant_rate_factor = 'HIGH'
r.ffmpeg.ffmpeg_preset = 'GOOD'
r.ffmpeg.audio_codec = 'AAC'
r.ffmpeg.audio_bitrate = 256
r.ffmpeg.audio_mixrate = 48000
r.ffmpeg.audio_channels = 'STEREO'
r.filepath = out
scene.render.use_sequencer = True
bpy.ops.render.render(animation=True)
print("ENCODE_OK", out, len(files))
