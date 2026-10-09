#!/usr/bin/env python3
"""Gives the Quaternius castle tower (assets/pack/tower.g3dj, CC0) a second door exactly opposite the one it came with, assets/pack/tower2.g3dj.
The door area of the wall (arch frame, doorway recess, base ring) is copied turned 180 degrees about the tower axis onto the opposite wall (replacing the plain wall there),
together with the black doorway backing and the wooden door leaf, so both doors are cut into the wall the same way. usage: tools/make_tower_door.py"""
import json, copy, os, math
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
g = json.load(open(os.path.join(ROOT, 'assets', 'pack', 'tower.g3dj')))
h = copy.deepcopy(g)
ST = 6                      # POSITION + NORMAL
CZ = -0.046                 # tower axis (x = 0, z = CZ), measured from the model's bounding box

def rot(v, tx=0.0, ty=0.0, tz=0.0):
    x, y, z, nx, ny, nz = v
    x += tx; y += ty; z += tz                       # node translation baked in
    return [-x, y, 2 * CZ - z, -nx, ny, -nz]

def in_door_region(x, y, z): return (z - CZ) > 2.0 and abs(x) < 2.2 and y < 5.8
def in_back_region(x, y, z): return in_door_region(-x, y, 2 * CZ - z)

def verts(m): v = m['vertices']; return [v[i:i + ST] for i in range(0, len(v), ST)]

mesh_by_part = {m['parts'][0]['id']: m for m in h['meshes']}
node0 = next(n for n in h['nodes'] if n['id'] == 'n0')
node1 = next(n for n in h['nodes'] if n['id'] == 'n1')
t1 = node1.get('translation', [0, 0, 0])

# 1) wall pieces: drop the plain back wall in the door area, add the turned copy of the front door area
for pid in ('p0_0', 'p0_3'):
    m = mesh_by_part[pid]; V = verts(m); part = m['parts'][0]
    idx = part['indices']; tris = [idx[i:i + 3] for i in range(0, len(idx), 3)]
    front = [t for t in tris if all(in_door_region(*V[k][:3]) for k in t)]
    keep = [t for t in tris if not all(in_back_region(*V[k][:3]) for k in t)]
    base = len(V); newv = []; newidx = []
    for t in front:
        for k in t:                                   # a 180 degree turn keeps the winding
            newidx.append(base + len(newv)); newv.append(rot(V[k]))
    m['vertices'] = [c for v in V + newv for c in v]
    part['indices'] = [k for t in keep for k in t] + newidx

# 2) the doorway backing and the door leaf, turned onto the back
def clone_part(src_pid, new_pid, node, tx=0.0, ty=0.0, tz=0.0):
    m = copy.deepcopy(mesh_by_part[src_pid]); V = verts(m)
    m['vertices'] = [c for v in V for c in rot(v, tx, ty, tz)]
    idx = m['parts'][0]['indices']; pass                                              # a 180 degree turn keeps the winding
    m['parts'][0]['id'] = new_pid
    h['meshes'].append(m)
    src = next(p for n in h['nodes'] for p in n['parts'] if p['meshpartid'] == src_pid)
    node['parts'].append({'meshpartid': new_pid, 'materialid': src['materialid']})
clone_part('p0_4', 'p0_4b', node0)
for pid in ('p1_0', 'p1_1', 'p1_2'): clone_part(pid, pid + 'b', node0, *t1)

h['id'] = 'tower2'
json.dump(h, open(os.path.join(ROOT, 'assets', 'pack', 'tower2.g3dj'), 'w'), separators=(',', ':'))
print('ok')
