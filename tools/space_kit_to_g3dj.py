#!/usr/bin/env python3
"""Convert Quaternius 'Ultimate Space Kit' glTF files (CC0; one shared palette-atlas texture, embedded buffers) into libGDX g3dj.
The atlas is a palette of flat colour cells, so each vertex's UV is sampled once and baked into a per-vertex COLOR attribute (exact, no texture needed).
Skinned models keep their skeleton and all animation clips; static ones are exported as-is.
usage: space_kit_to_g3dj.py out_dir model.gltf [model.gltf ...]"""
import json, sys, base64, struct, os, io
import numpy as np
from PIL import Image

CT = {5120: ('b', 1), 5121: ('B', 1), 5122: ('h', 2), 5123: ('H', 2), 5125: ('I', 4), 5126: ('f', 4)}
NC = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}

def r(v, d=5): return [round(float(x), d) for x in v]

def decompose(m):
    t = m[:3, 3].copy()
    sc = [np.linalg.norm(m[:3, c]) for c in range(3)]
    rm = np.stack([m[:3, c] / sc[c] for c in range(3)], axis=1)
    tr = rm[0, 0] + rm[1, 1] + rm[2, 2]
    if tr > 0:
        s = np.sqrt(tr + 1) * 2; w = 0.25 * s; x = (rm[2, 1] - rm[1, 2]) / s; y = (rm[0, 2] - rm[2, 0]) / s; z = (rm[1, 0] - rm[0, 1]) / s
    elif rm[0, 0] > rm[1, 1] and rm[0, 0] > rm[2, 2]:
        s = np.sqrt(1 + rm[0, 0] - rm[1, 1] - rm[2, 2]) * 2; w = (rm[2, 1] - rm[1, 2]) / s; x = 0.25 * s; y = (rm[0, 1] + rm[1, 0]) / s; z = (rm[0, 2] + rm[2, 0]) / s
    elif rm[1, 1] > rm[2, 2]:
        s = np.sqrt(1 + rm[1, 1] - rm[0, 0] - rm[2, 2]) * 2; w = (rm[0, 2] - rm[2, 0]) / s; x = (rm[0, 1] + rm[1, 0]) / s; y = 0.25 * s; z = (rm[1, 2] + rm[2, 1]) / s
    else:
        s = np.sqrt(1 + rm[2, 2] - rm[0, 0] - rm[1, 1]) * 2; w = (rm[1, 0] - rm[0, 1]) / s; x = (rm[0, 2] + rm[2, 0]) / s; y = (rm[1, 2] + rm[2, 1]) / s; z = 0.25 * s
    return t, [x, y, z, w], sc

def convert(src, out_dir):
    g = json.load(open(src))
    uri = g['buffers'][0]['uri']
    buf = base64.b64decode(uri.split(',', 1)[1]) if uri.startswith('data:') else open(os.path.join(os.path.dirname(src), uri), 'rb').read()

    def acc(i):
        a = g['accessors'][i]; bv = g['bufferViews'][a['bufferView']]
        fmt, size = CT[a['componentType']]; n = NC[a['type']]
        off = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
        stride = bv.get('byteStride') or size * n
        return np.array([struct.unpack_from('<' + fmt * n, buf, off + k * stride) for k in range(a['count'])], dtype=np.float64 if fmt == 'f' else np.int64)

    im = g['images'][0]
    if 'bufferView' in im:
        bv = g['bufferViews'][im['bufferView']]; data = buf[bv.get('byteOffset', 0):bv.get('byteOffset', 0) + bv['byteLength']]
    elif im['uri'].startswith('data:'): data = base64.b64decode(im['uri'].split(',', 1)[1])
    else: data = open(os.path.join(os.path.dirname(src), im['uri']), 'rb').read()
    atlas = np.array(Image.open(io.BytesIO(data)).convert('RGBA')); H, W = atlas.shape[:2]

    def colour(uv):
        x = np.clip((uv[:, 0] * W).astype(int), 0, W - 1); y = np.clip((uv[:, 1] * H).astype(int), 0, H - 1)
        return atlas[y, x] / 255.0

    nodes = g['nodes']
    def nid(i): return 'n%d_%s' % (i, nodes[i].get('name', '').replace(' ', '_'))
    skin = g['skins'][0] if g.get('skins') else None
    joints = skin['joints'] if skin else []
    bones = []
    if skin:
        ibm = np.array([m.T for m in acc(skin['inverseBindMatrices']).reshape(-1, 4, 4)])
        for j, node in enumerate(joints):
            t, q, s = decompose(np.linalg.inv(ibm[j]))
            bones.append({'node': nid(node), 'translation': r(t), 'rotation': r(q), 'scale': r(s)})

    # drop the finger bones (the game never poses them and the shader allows 32 bones): their vertices follow the nearest kept ancestor
    parent = {}
    def mark(i, par):
        parent[i] = par
        for c in nodes[i].get('children', []): mark(c, i)
    for rt in g['scenes'][g.get('scene', 0)]['nodes']: mark(rt, None)
    def pruned(node): return any(k in nodes[node].get('name', '') for k in ('Pinky', 'Middle', 'Index', 'Thumb', 'Pistol'))
    keep = [n for n in joints if not pruned(n)]
    newidx = {n: k for k, n in enumerate(keep)}
    def remap(j):
        n = joints[j]
        while n is not None and n not in newidx: n = parent.get(n)
        return newidx[n] if n is not None else 0
    jmap = [remap(j) for j in range(len(joints))]
    bones = [bones[joints.index(n)] for n in keep] if skin else []

    meshes = []
    materials = [{'id': 'mat0_atlas', 'diffuse': [1.0, 1.0, 1.0]}]

    def parts_of(i):
        n = nodes[i]; parts = []
        for pi, p in enumerate(g['meshes'][n['mesh']]['primitives']):
            pos = acc(p['attributes']['POSITION']); nor = acc(p['attributes']['NORMAL']); col = colour(acc(p['attributes']['TEXCOORD_0']))
            skinned = 'JOINTS_0' in p['attributes']
            if skinned: jt = acc(p['attributes']['JOINTS_0']); wt = acc(p['attributes']['WEIGHTS_0'])
            verts = []
            for k in range(len(pos)):
                verts += r(pos[k]) + r(nor[k]) + r(col[k], 4)
                if skinned:
                    acc_w = {}
                    for slot in range(4):
                        if wt[k][slot] > 0: acc_w[jmap[int(jt[k][slot])]] = acc_w.get(jmap[int(jt[k][slot])], 0.0) + float(wt[k][slot])
                    items = sorted(acc_w.items(), key=lambda kv: -kv[1])[:4]
                    for slot in range(4):
                        j, w_ = items[slot] if slot < len(items) else (0, 0.0)
                        verts += [float(j), round(w_, 5)]
            attrs = ['POSITION', 'NORMAL', 'COLOR'] + (['BLENDWEIGHT0', 'BLENDWEIGHT1', 'BLENDWEIGHT2', 'BLENDWEIGHT3'] if skinned else [])
            pid = 'p%d_%d' % (i, pi)
            meshes.append({'attributes': attrs, 'vertices': verts, 'parts': [{'id': pid, 'type': 'TRIANGLES', 'indices': acc(p['indices']).flatten().astype(int).tolist()}]})
            part = {'meshpartid': pid, 'materialid': 'mat0_atlas'}
            if skinned: part['bones'] = bones; part['uvMapping'] = []
            parts.append(part)
        return parts

    def conv(i):
        n = nodes[i]; o = {'id': nid(i)}
        if 'matrix' in n:
            t, q, s = decompose(np.array(n['matrix']).reshape(4, 4).T); o['translation'], o['rotation'], o['scale'] = r(t), r(q), r(s)
        else:
            if n.get('translation'): o['translation'] = r(n['translation'])
            if n.get('rotation'): o['rotation'] = r(n['rotation'])
            if n.get('scale'): o['scale'] = r(n['scale'])
        if 'mesh' in n: o['parts'] = parts_of(i)
        if n.get('children'): o['children'] = [conv(c) for c in n['children']]
        return o

    sc = g['scenes'][g.get('scene', 0)]
    roots = [conv(i) for i in sc['nodes']]
    anims = []
    for a in g.get('animations', []):
        per = {}
        for ch in a['channels']:
            s = a['samplers'][ch['sampler']]
            per.setdefault(ch['target']['node'], {})[ch['target']['path']] = (acc(s['input']).flatten(), acc(s['output']))
        bl = []
        for node, paths in per.items():
            kfs = [(float(tm), path, r(v)) for path, (times, vals) in paths.items() for tm, v in zip(times, vals)]
            kfs.sort(key=lambda k: k[0])
            bl.append({'boneId': nid(node), 'keyframes': [{'keytime': round(t * 1000, 2), path: v} for t, path, v in kfs]})
        anims.append({'id': a['name'], 'bones': bl})
    name = os.path.splitext(os.path.basename(src))[0].lower()
    if '-' in name and len(name.split('-')[0]) == 8: name = name.split('-', 1)[1]
    json.dump({'version': [0, 1], 'id': name, 'meshes': meshes, 'materials': materials, 'nodes': roots, 'animations': anims}, open(os.path.join(out_dir, name + '.g3dj'), 'w'), separators=(',', ':'))
    print('ok', name, len(meshes), 'meshes', len(anims), 'anims')

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
for f in sys.argv[2:]: convert(f, out)
