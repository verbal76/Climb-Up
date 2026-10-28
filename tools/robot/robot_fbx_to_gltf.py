import bpy, sys, os, json, base64
src, dst = sys.argv[-2], sys.argv[-1]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.fbx(filepath=src)
arm = bpy.data.objects['RobotArmature']
meshes = [o for o in bpy.data.objects if o.type == 'MESH']
for o in meshes:
    if o.parent_type == 'BONE':
        bone = o.parent_bone; mw = o.matrix_world.copy()
        o.parent = None; o.matrix_world = mw
        vg = o.vertex_groups.new(name=bone); vg.add(list(range(len(o.data.vertices))), 1.0, 'REPLACE')
        m = o.modifiers.new('Armature', 'ARMATURE'); m.object = arm
        o.parent = arm; o.parent_type = 'OBJECT'; o.matrix_world = mw
bpy.ops.object.select_all(action='DESELECT')
for o in meshes: o.select_set(True)
bpy.context.view_layer.objects.active = meshes[0]
bpy.ops.object.join()
body = bpy.context.view_layer.objects.active; body.name = 'Robot'
print("JOINED", body.name, len(body.data.vertices), [m.name for m in body.data.materials], [mod.type for mod in body.modifiers])
for mat in body.data.materials:
    n = mat.node_tree.nodes.get('Principled BSDF') if mat.use_nodes else None
    print("MAT", mat.name, tuple(round(x, 3) for x in (n.inputs['Base Color'].default_value if n else mat.diffuse_color)))
bpy.ops.export_scene.gltf(filepath=dst, export_format='GLTF_SEPARATE', export_animations=True, export_skins=True, export_animation_mode='ACTIONS', export_yup=True)
g = json.load(open(dst)); d = os.path.dirname(dst)
for b in g['buffers']:
    raw = open(os.path.join(d, b['uri']), 'rb').read(); b['uri'] = 'data:application/octet-stream;base64,' + base64.b64encode(raw).decode()
json.dump(g, open(dst, 'w'))
print("ANIMS", [a['name'] for a in g.get('animations', [])], "SKINS", len(g.get('skins', [])), "MESHES", len(g['meshes']))
