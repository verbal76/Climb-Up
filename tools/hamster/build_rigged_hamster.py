"""Builds assets/hero/hamster.g3dj: the owner's chibi hamster (a static mesh, no skeleton) bound to a real skeleton and given all of the bunny's animation clips.

The skeleton is the Quaternius 'Character' skeleton (same bone names and the same local bone rotations as assets/hero/hero.g3dj) with every joint moved to a hamster landmark,
so the bunny's keyframes (absolute local rotations) drive the hamster exactly the same way; translation keys are re-based on the hamster's own bone lengths.
Skin weights are computed from the vertex position (spine height gradient, arm stubs, feet, ears, head).

usage (needs Blender's bpy):  python build_rigged_hamster.py <hamster.blend> <hero.g3dj> <out.g3dj>
"""
import bpy, bmesh, sys, json, math
import numpy as np

blend, hero_path, out_path = sys.argv[-3], sys.argv[-2], sys.argv[-1]

# ------------------------------------------------------------------ mesh from Blender (+ island ids)
bpy.ops.wm.open_mainfile(filepath=blend)
o = bpy.data.objects['Hamster']
dg = bpy.context.evaluated_depsgraph_get()
oe = o.evaluated_get(dg); me = oe.to_mesh(); M = o.matrix_world
me.transform(M); me.calc_loop_triangles()
uv = me.uv_layers.active.data; nrm = me.corner_normals

bm = bmesh.new(); bm.from_mesh(me)
isl = {}; seen = set(); nisl = 0
for v in bm.verts:
    if v.index in seen: continue
    st = [v]; seen.add(v.index)
    while st:
        a = st.pop(); isl[a.index] = nisl
        for e in a.link_edges:
            b = e.other_vert(a)
            if b.index not in seen: seen.add(b.index); st.append(b)
    nisl += 1

verts = []; idx = {}; tris = []
for tri in me.loop_triangles:
    for li in tri.loops:
        vi = me.loops[li].vertex_index
        p = me.vertices[vi].co; n = nrm[li].vector; u = uv[li].uv
        key = (vi, round(n[0], 3), round(n[1], 3), round(n[2], 3), round(u[0], 4), round(u[1], 4))
        if key not in idx:
            idx[key] = len(verts); verts.append(((p[0], p[2], -p[1]), (n[0], n[2], -n[1]), (u[0], 1.0 - u[1]), isl[vi], p[2]))
        tris.append(idx[key])
P = np.array([v[0] for v in verts]); mn = P.min(0); mx = P.max(0)
ctr = np.array([(mn[0] + mx[0]) / 2, mn[1], (mn[2] + mx[2]) / 2])
K = 3.7 / (mx[1] - mn[1])            # same overall height as the bunny (ears included) so the renderer's scale fits both
print('bbox', mn, mx, 'islands', nisl, 'K', K)

# island classes (found by inspection of the mesh): the two big ones near the top are the ears
isl_bbox = {}
for v in verts:
    i = v[3]; p = np.array(v[0])
    if i not in isl_bbox: isl_bbox[i] = [p.copy(), p.copy()]
    isl_bbox[i][0] = np.minimum(isl_bbox[i][0], p); isl_bbox[i][1] = np.maximum(isl_bbox[i][1], p)
ear_islands = {i for i, (lo, hi) in isl_bbox.items() if lo[1] > 2.5 and hi[1] > 3.0}
body_island = max(isl_bbox, key=lambda i: sum(1 for v in verts if v[3] == i))
print('ears', ear_islands, 'body', body_island)

# ------------------------------------------------------------------ skeleton
hero = json.load(open(hero_path))
def find_armature(ns):
    for n in ns:
        if n['id'].endswith('CharacterArmature'): return n
    return None
arm = find_armature(hero['nodes'])
nodes = {}
def reg(n, parent):
    nodes[n['id']] = {'id': n['id'], 'parent': parent, 'q': np.array(n.get('rotation', [0, 0, 0, 1]), float), 't': np.array(n.get('translation', [0, 0, 0]), float), 'kids': []}
    if parent: nodes[parent]['kids'].append(n['id'])
    for c in n.get('children', []):
        if 'parts' in c and 'translation' not in c and 'rotation' not in c and not c.get('children'): continue   # skinned mesh nodes
        reg(c, n['id'])
root_id = [c for c in arm['children'] if c['id'].endswith('_Root')][0]['id']
for c in arm['children']:
    if c['id'] == root_id: reg(c, None)

def qmul(a, b):
    x1, y1, z1, w1 = a; x2, y2, z2, w2 = b
    return np.array([w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2, w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2, w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2, w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2])
def qrot(q, v):
    x, y, z, w = q; u = np.array([x, y, z])
    return v + 2 * np.cross(u, np.cross(u, v) + w * v)
def qinv(q): return np.array([-q[0], -q[1], -q[2], q[3]]) / np.dot(q, q)

# global rotation of every bone at rest (bunny local rotations are kept)
rg = {}
def comp(i, pq):
    nodes[i]['qg'] = qmul(pq, nodes[i]['q']);
    for k in nodes[i]['kids']: comp(k, nodes[i]['qg'])
comp(root_id, np.array([0, 0, 0, 1.0]))

def name_of(i): return i.split('_', 1)[1]
by_name = {name_of(i): i for i in nodes}

# hamster joint landmarks: (x, height, depth) in the mesh's own units, then scaled by K
L = {
    'Root': (0, 0, 0), 'Body': (0, 0.70, 0), 'Hips': (0, 0.55, 0), 'Abdomen': (0, 0.95, 0), 'Torso': (0, 1.30, 0), 'Neck': (0, 1.55, 0), 'Head': (0, 1.65, 0),
    'Foot.L': (0.50, 0.05, 0.15), 'Foot.R': (-0.50, 0.05, 0.15), 'PoleTarget.L': (0.50, 0.50, 0.90), 'PoleTarget.R': (-0.50, 0.50, 0.90),
}
for sd, s in (('L', 1), ('R', -1)):
    L['Ear1.' + sd] = (0.55 * s, 2.70, 0); L['Ear2.' + sd] = (0.55 * s, 2.98, 0); L['Ear3.' + sd] = (0.55 * s, 3.20, 0)
    L['Shoulder.' + sd] = (0.70 * s, 1.25, 0); L['UpperArm.' + sd] = (0.85 * s, 1.25, 0); L['LowerArm.' + sd] = (1.05 * s, 1.25, 0); L['Fist.' + sd] = (1.25 * s, 1.25, 0)
    L['UpperLeg.' + sd] = (0.50 * s, 0.70, 0); L['LowerLeg.' + sd] = (0.50 * s, 0.38, 0)
for i, nd in nodes.items():
    nd['p'] = np.array(L[name_of(i)], float) * K
    nd['rest_bunny_t'] = nd['t'].copy()
for i, nd in nodes.items():
    par = nd['parent']
    nd['t_h'] = nd['p'] if par is None else qrot(qinv(nodes[par]['qg']), nd['p'] - nodes[par]['p'])

# ------------------------------------------------------------------ skin weights
bone_list = [b['node'] for b in next(p for n in arm['children'] if 'parts' in n for p in n['parts'])['bones']]
bidx = {b: k for k, b in enumerate(bone_list)}
def J(name): return bidx[by_name[name]]
def sstep(x): x = max(0.0, min(1.0, x)); return x * x * (3 - 2 * x)

def weights(v):
    (x, y, z), isl_id, zb = v[0], v[3], v[4]
    xb = x - ctr[0]
    h = zb - mn[1] * 0 - 0.0           # blender z == height above the feet (feet at ~0)
    w = {}
    def add(name, wt):
        if wt > 1e-4: w[J(name)] = w.get(J(name), 0) + wt
    sd = 'L' if xb >= 0 else 'R'
    if isl_id in ear_islands:
        sd = 'L' if xb >= 0 else 'R'
        a = sstep((h - 2.72) / 0.25); b = sstep((h - 2.98) / 0.2); c = sstep((h - 3.12) / 0.14)
        add('Head', 1 - a); add('Ear1.' + sd, a * (1 - b)); add('Ear2.' + sd, b * (1 - c)); add('Ear3.' + sd, c)
    elif isl_id != body_island:
        add('Head', 1.0)                                                   # head shell, eyes, nose, whiskers
    else:
        ax = abs(xb)
        arm = sstep((ax - 0.62) / 0.3) * sstep((h - 0.95) / 0.2)         # the little arm stubs stick out at chest height
        leg = sstep((0.70 - h) / 0.35) * sstep(ax / 0.30)                # feet and lower belly
        arm = min(arm, 1.0); leg = min(leg, 1.0 - arm)
        spine = 1.0 - arm - leg
        lowerArm = sstep((ax - 1.0) / 0.12); lowerLeg = sstep((0.42 - h) / 0.2)
        add('UpperArm.' + sd, arm * (1 - lowerArm)); add('LowerArm.' + sd, arm * lowerArm)
        add('UpperLeg.' + sd, leg * (1 - lowerLeg)); add('LowerLeg.' + sd, leg * lowerLeg)
        # spine: hat functions over Hips / Abdomen / Torso
        hh = h
        wh = max(0.0, min(1.0, (0.95 - hh) / 0.40)) if hh < 0.95 else 0.0
        wt_ = max(0.0, min(1.0, (hh - 0.95) / 0.40)) if hh > 0.95 else 0.0
        wa = 1.0 - wh - wt_
        add('Hips', spine * wh); add('Abdomen', spine * wa); add('Torso', spine * wt_)
    top = sorted(w.items(), key=lambda kv: -kv[1])[:4]
    tot = sum(t[1] for t in top)
    return [(j, wt / tot) for j, wt in top]

flat = []
for v in verts:
    p = (np.array(v[0]) - ctr) * K; n = v[1]; u = v[2]
    ws = weights(v)
    flat += [round(p[0], 4), round(p[1], 4), round(p[2], 4), round(n[0], 3), round(n[1], 3), round(n[2], 3), round(u[0], 4), round(u[1], 4)]
    for s in range(4):
        j, wt = ws[s] if s < len(ws) else (0, 0.0)
        flat += [float(j), round(wt, 5)]

# ------------------------------------------------------------------ nodes, bones, animations
def node_json(i):
    nd = nodes[i]
    o = {'id': i, 'translation': [round(float(c), 5) for c in nd['t_h']], 'rotation': [round(float(c), 5) for c in nd['q']], 'scale': [1.0, 1.0, 1.0]}
    if nd['kids']: o['children'] = [node_json(k) for k in nd['kids']]
    return o

bones = []
for b in bone_list:
    bones.append({'node': b, 'translation': [round(float(c), 5) for c in nodes[b]['p']], 'rotation': [round(float(c), 5) for c in nodes[b]['qg']], 'scale': [1.0, 1.0, 1.0]})
mesh_node = {'id': 'n32_HamBody', 'parts': [{'meshpartid': 'part_ham', 'materialid': 'mat1', 'bones': bones, 'uvMapping': [[0]]}]}
armature = {'id': arm['id'], 'children': [mesh_node, node_json(root_id)]}

anims = []
for a in hero['animations']:
    nb = []
    for bn in a['bones']:
        i = bn['boneId']
        if i not in nodes: continue
        kfs = []
        for kf in bn['keyframes']:
            k = dict(kf)
            if 'translation' in k:
                k['translation'] = [round(float(c), 5) for c in nodes[i]['t_h'] + (np.array(k['translation']) - nodes[i]['rest_bunny_t'])]
            kfs.append(k)
        nb.append({'boneId': i, 'keyframes': kfs})
    anims.append({'id': a['id'], 'bones': nb})

g3 = {'version': [0, 1], 'id': 'hamster',
      'meshes': [{'attributes': ['POSITION', 'NORMAL', 'TEXCOORD0', 'BLENDWEIGHT0', 'BLENDWEIGHT1', 'BLENDWEIGHT2', 'BLENDWEIGHT3'], 'vertices': flat,
                  'parts': [{'id': 'part_ham', 'type': 'TRIANGLES', 'indices': tris}]}],
      'materials': [{'id': 'mat1', 'diffuse': [1, 1, 1], 'textures': [{'id': 'tex1', 'filename': 'hamster.png', 'type': 'DIFFUSE'}]}],
      'nodes': [armature], 'animations': anims}
json.dump(g3, open(out_path, 'w'), separators=(',', ':'))
print('wrote', out_path, len(verts), 'verts', len(anims), 'clips')
