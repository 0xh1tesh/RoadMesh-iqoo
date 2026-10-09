"""Headless Blender: render a vehicle GLB the way a phone would see it in AR, for YOLO checks.

    blender -b -P tools/blender_views.py -- <model.glb> <out_dir> [paint_hex,...]

Phone-like camera (26 mm equivalent, portrait, 1.4 m eye height), the car on a ground plane under
an outdoor HDRI, 12 yaw angles x 3 distances x the given paint colours. tools/yolo_check.py then
scores every frame with YOLO.
"""
import math
import os
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
glb, out = os.path.abspath(argv[0]), os.path.abspath(argv[1])
paints = (argv[2] if len(argv) > 2 else "B3122A,E8E8E8,1F4FB8").split(",")
os.makedirs(out, exist_ok=True)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
engines = [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items]
scene.render.engine = next(e for e in engines if "EEVEE" in e)
scene.render.resolution_x, scene.render.resolution_y = 540, 1080
scene.render.image_settings.file_format = "JPEG"
try:
    scene.eevee.taa_render_samples = 16
except AttributeError:
    pass

bpy.ops.import_scene.gltf(filepath=glb)
meshes = [o for o in scene.objects if o.type == "MESH"]
pts = [o.matrix_world @ Vector(c) for o in meshes for c in o.bound_box]
mn = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
mx = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
roots = [o for o in scene.objects if o.parent is None]
shift = Vector((-(mn.x + mx.x) / 2, -(mn.y + mx.y) / 2, -mn.z))
for r in roots:
    r.location += shift
size = mx - mn
print("CAR_SIZE", tuple(round(v, 3) for v in size))

# World: Blender's bundled outdoor HDRI.
world = bpy.data.worlds.new("w")
scene.world = world
world.use_nodes = True
nt = world.node_tree
env = nt.nodes.new("ShaderNodeTexEnvironment")
hdr_dir = bpy.utils.system_resource("DATAFILES", path="studiolights/world")
env.image = bpy.data.images.load(os.path.join(hdr_dir, "city.exr"))
bg = next(n for n in nt.nodes if n.type == "BACKGROUND")
nt.links.new(env.outputs["Color"], bg.inputs["Color"])

# Ground: a grey asphalt-ish plane.
bpy.ops.mesh.primitive_plane_add(size=200)
ground = bpy.context.active_object
gm = bpy.data.materials.new("ground")
gm.use_nodes = True
p = next(n for n in gm.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
p.inputs["Base Color"].default_value = (0.16, 0.16, 0.17, 1)
p.inputs["Roughness"].default_value = 0.9
ground.data.materials.append(gm)

cam_data = bpy.data.cameras.new("cam")
cam_data.lens, cam_data.sensor_width = 26, 36
cam = bpy.data.objects.new("cam", cam_data)
scene.collection.objects.link(cam)
scene.camera = cam

paint_mats = [m for m in bpy.data.materials if m.name.startswith("Paint 1")]
target = Vector((0, 0, size.z * 0.45))
for hexc in paints:
    rgb = tuple(int(hexc[i:i + 2], 16) / 255 for i in (0, 2, 4))
    lin = tuple(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in rgb)
    for m in paint_mats:
        for n in m.node_tree.nodes:
            if n.type == "BSDF_PRINCIPLED":
                n.inputs["Base Color"].default_value = (*lin, 1)
    for dist in (4.0, 8.0, 15.0):
        for deg in range(0, 360, 30):
            a = math.radians(deg)
            cam.location = Vector((dist * math.sin(a), dist * math.cos(a), 1.4))
            cam.rotation_euler = (target - cam.location).to_track_quat("-Z", "Y").to_euler()
            scene.render.filepath = os.path.join(out, f"{hexc}_d{int(dist):02d}_a{deg:03d}.jpg")
            bpy.ops.render.render(write_still=True)
print("DONE")
