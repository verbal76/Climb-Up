#!/usr/bin/env python3
"""Convert a skinned, animated glTF (embedded buffers, flat-colour materials) into libGDX g3dj. Used for the Quaternius 'Character' (CC0).
usage: gltf_to_g3dj.py in.gltf out.g3dj"""
import json, sys, base64, struct
import numpy as np

src, dst = sys.argv[1], sys.argv[2]
g = json.load(open(src))
buf = base64.b64decode(g['buffers'][0]['uri'].split(',', 1)[1])
CT = {5120: ('b', 1), 5121: ('B', 1), 5122: ('h', 2), 5123: ('H', 2), 5125: ('I', 4), 5126: ('f', 4)}
NC = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}

def acc(i):
    a = g['accessors'][i]; bv = g['bufferViews'][a['bufferView']]
    fmt, size = CT[a['componentType']]; n = NC[a['type']]
    off = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
    stride = bv.get('byteStride') or size * n
    out = []
    for k in range(a['count']):
        out.append(struct.unpack_from('<' + fmt * n, buf, off + k * stride))
    return np.array(out, dtype=np.float64 if fmt == 'f' else np.int64)

def r(v): return [round(float(x), 5) for x in v]

def trs_of(n):
    t = n.get('translation'); q = n.get('rotation'); s = n.get('scale')
    return t, q, s

def decompose(m):
    t = m[:3, 3].copy()
    sx, sy, sz = (np.linalg.norm(m[:3, c]) for c in range(3))
    rm = np.stack([m[:3, 0] / sx, m[:3, 1] / sy, m[:3, 2] / sz], axis=1)
    tr = rm[0, 0] + rm[1, 1] + rm[2, 2]
    if tr > 0:
        s = np.sqrt(tr + 1) * 2; w = 0.25 * s; x = (rm[2, 1] - rm[1, 2]) / s; y = (rm[0, 2] - rm[2, 0]) / s; z = (rm[1, 0] - rm[0, 1]) / s
    elif rm[0, 0] > rm[1, 1] and rm[0, 0] > rm[2, 2]:
        s = np.sqrt(1 + rm[0, 0] - rm[1, 1] - rm[2, 2]) * 2; w = (rm[2, 1] - rm[1, 2]) / s; x = 0.25 * s; y = (rm[0, 1] + rm[1, 0]) / s; z = (rm[0, 2] + rm[2, 0]) / s
    elif rm[1, 1] > rm[2, 2]:
        s = np.sqrt(1 + rm[1, 1] - rm[0, 0] - rm[2, 2]) * 2; w = (rm[0, 2] - rm[2, 0]) / s; x = (rm[0, 1] + rm[1, 0]) / s; y = 0.25 * s; z = (rm[1, 2] + rm[2, 1]) / s
    else:
        s = np.sqrt(1 + rm[2, 2] - rm[0, 0] - rm[1, 1]) * 2; w = (rm[1, 0] - rm[0, 1]) / s; x = (rm[0, 2] + rm[2, 0]) / s; y = (rm[1, 2] + rm[2, 1]) / s; z = 0.25 * s
    return t, [x, y, z, w], [sx, sy, sz]

nodes = g['nodes']
def nid(i): return 'n%d_%s' % (i, nodes[i].get('name', '').replace(' ', '_'))

skin = g['skins'][0]; joints = skin['joints']
ibm = acc(skin['inverseBindMatrices']).reshape(-1, 4, 4)           # glTF is column-major -> transpose rows
ibm = np.array([m.T for m in ibm])
bones = []
for j, node in enumerate(joints):
    bind = np.linalg.inv(ibm[j])
    t, q, s = decompose(bind)
    bones.append({'node': nid(node), 'translation': r(t), 'rotation': r(q), 'scale': r(s)})

def srgb(c): return [round(float(x) ** (1 / 2.2), 4) for x in c[:3]]
materials = []
for i, m in enumerate(g['materials']):
    materials.append({'id': 'mat%d_%s' % (i, m['name']), 'diffuse': srgb(m['pbrMetallicRoughness']['baseColorFactor'])})

meshes, nodes_out = [], []
def convert_mesh_node(i):
    n = nodes[i]; m = g['meshes'][n['mesh']]; parts = []
    for pi, p in enumerate(m['primitives']):
        pos = acc(p['attributes']['POSITION']); nor = acc(p['attributes']['NORMAL'])
        jt = acc(p['attributes']['JOINTS_0']); wt = acc(p['attributes']['WEIGHTS_0'])
        verts = []
        for k in range(len(pos)):
            verts += r(pos[k]) + r(nor[k])
            for slot in range(4): verts += [float(jt[k][slot]), round(float(wt[k][slot]), 5)]
        idx = acc(p['indices']).flatten().astype(int).tolist()
        pid = 'part_%d_%d' % (i, pi)
        meshes.append({'attributes': ['POSITION', 'NORMAL', 'BLENDWEIGHT0', 'BLENDWEIGHT1', 'BLENDWEIGHT2', 'BLENDWEIGHT3'], 'vertices': verts,
                       'parts': [{'id': pid, 'type': 'TRIANGLES', 'indices': idx}]})
        parts.append({'meshpartid': pid, 'materialid': materials[p['material']]['id'], 'bones': bones, 'uvMapping': []})
    return parts

def convert(i):
    n = nodes[i]; o = {'id': nid(i)}
    if 'matrix' in n:
        t, q, s = decompose(np.array(n['matrix']).reshape(4, 4).T); o['translation'], o['rotation'], o['scale'] = r(t), r(q), r(s)
    else:
        t, q, s = trs_of(n)
        if t: o['translation'] = r(t)
        if q: o['rotation'] = r(q)
        if s: o['scale'] = r(s)
    if 'mesh' in n: o['parts'] = convert_mesh_node(i)
    if n.get('children'): o['children'] = [convert(c) for c in n['children']]
    return o

scene = g['scenes'][g.get('scene', 0)]
nodes_out = [convert(i) for i in scene['nodes']]

anims = []
for a in g['animations']:
    per = {}   # node -> {path: [(t, value)]}
    for ch in a['channels']:
        s = a['samplers'][ch['sampler']]
        times = acc(s['input']).flatten(); vals = acc(s['output'])
        per.setdefault(ch['target']['node'], {})[ch['target']['path']] = (times, vals)
    bl = []
    for node, paths in per.items():
        kfs = []
        for path, (times, vals) in paths.items():
            for tm, v in zip(times, vals):
                kfs.append((float(tm), path, r(v)))
        kfs.sort(key=lambda k: k[0])
        bl.append({'boneId': nid(node), 'keyframes': [{'keytime': round(t * 1000, 2), path: v} for t, path, v in kfs]})
    anims.append({'id': a['name'], 'bones': bl})

json.dump({'version': [0, 1], 'id': 'hero', 'meshes': meshes, 'materials': materials, 'nodes': nodes_out, 'animations': anims}, open(dst, 'w'), separators=(',', ':'))
print('ok', len(meshes), 'meshes', len(joints), 'joints', len(anims), 'animations')
