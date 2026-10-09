#!/usr/bin/env python3
"""Convert static (or bind-posed) flat-colour glTF models from the Quaternius Ultimate Platformer Pack (CC0) into one libGDX g3dj file each.
Skins/animations are ignored: skinned meshes are exported in their bind pose.
usage: gltf_static_to_g3dj.py out_dir model.gltf [model.gltf ...]"""
import json, sys, base64, struct, os
import numpy as np

CT = {5120: ('b', 1), 5121: ('B', 1), 5122: ('h', 2), 5123: ('H', 2), 5125: ('I', 4), 5126: ('f', 4)}
NC = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}

def r(v): return [round(float(x), 5) for x in v]
def srgb(c): return [round(float(x) ** (1 / 2.2), 4) for x in c[:3]]

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

    mats = []
    for i, m in enumerate(g['materials']):
        mats.append({'id': 'mat%d_%s' % (i, m['name'].replace(' ', '_')), 'diffuse': srgb(m['pbrMetallicRoughness']['baseColorFactor'])})
    meshes = []
    nodes = g['nodes']

    def conv(i):
        n = nodes[i]; o = {'id': 'n%d' % i}
        skinned = 'skin' in n
        if 'matrix' in n and not skinned:
            m = np.array(n['matrix']).reshape(4, 4).T
            t = m[:3, 3]; sc = [np.linalg.norm(m[:3, c]) for c in range(3)]
            o['translation'] = r(t); o['scale'] = r(sc)
            rm = np.stack([m[:3, c] / sc[c] for c in range(3)], axis=1)
            tr = rm[0, 0] + rm[1, 1] + rm[2, 2]
            if tr > 0:
                s = np.sqrt(tr + 1) * 2; q = [(rm[2, 1] - rm[1, 2]) / s, (rm[0, 2] - rm[2, 0]) / s, (rm[1, 0] - rm[0, 1]) / s, 0.25 * s]
                o['rotation'] = r(q)
        elif not skinned:
            if n.get('translation'): o['translation'] = r(n['translation'])
            if n.get('rotation'): o['rotation'] = r(n['rotation'])
            if n.get('scale'): o['scale'] = r(n['scale'])
        if 'mesh' in n:
            parts = []
            for pi, p in enumerate(g['meshes'][n['mesh']]['primitives']):
                pos = acc(p['attributes']['POSITION']); nor = acc(p['attributes']['NORMAL'])
                verts = []
                for k in range(len(pos)): verts += r(pos[k]) + r(nor[k])
                idx = acc(p['indices']).flatten().astype(int).tolist()
                pid = 'p%d_%d' % (i, pi)
                meshes.append({'attributes': ['POSITION', 'NORMAL'], 'vertices': verts, 'parts': [{'id': pid, 'type': 'TRIANGLES', 'indices': idx}]})
                parts.append({'meshpartid': pid, 'materialid': mats[p['material']]['id']})
            o['parts'] = parts
        if n.get('children'):
            ch = [conv(c) for c in n['children'] if 'joints' not in g['skins'][0] or c not in g['skins'][0]['joints']] if g.get('skins') else [conv(c) for c in n['children']]
            if ch: o['children'] = ch
        return o

    sc = g['scenes'][g.get('scene', 0)]
    roots = [conv(i) for i in sc['nodes'] if not (g.get('skins') and i in g['skins'][0]['joints'])]
    name = os.path.splitext(os.path.basename(src))[0].lower()
    json.dump({'version': [0, 1], 'id': name, 'meshes': meshes, 'materials': mats, 'nodes': roots, 'animations': []}, open(os.path.join(out_dir, name + '.g3dj'), 'w'), separators=(',', ':'))
    print('ok', name, len(meshes), 'meshes')

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
for f in sys.argv[2:]: convert(f, out)
