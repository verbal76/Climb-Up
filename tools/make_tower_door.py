#!/usr/bin/env python3
"""Cuts the arched door (stone arch frame + wooden door leaf) out of the Quaternius castle tower into its own model, assets/pack/tower_door.g3dj,
so the game can mount a second door exactly opposite the tower's own door. usage: tools/make_tower_door.py"""
import json, copy, os
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
g = json.load(open(os.path.join(ROOT, 'assets', 'pack', 'tower.g3dj')))
h = copy.deepcopy(g)
meshes = []
for m in h['meshes']:
    pid = m['parts'][0]['id']
    if pid == 'p0_0':      # tower stone: keep only the arch around the door (above the base ring), not the wall or the ring
        v = m['vertices']; V = [v[i:i + 6] for i in range(0, len(v), 6)]
        idx = m['parts'][0]['indices']; tris = [idx[i:i + 3] for i in range(0, len(idx), 3)]
        sel = [t for t in tris if all(V[k][2] > 2.8 and abs(V[k][0]) < 2.0 and 1.45 <= V[k][1] <= 5.9 for k in t)]
        m['parts'][0]['indices'] = [k for t in sel for k in t]
        meshes.append(m)
    elif pid in ('p1_0', 'p1_1', 'p1_2'):     # the door leaf (its node carries the translation into place)
        meshes.append(m)
keep = {'p0_0', 'p1_0', 'p1_1', 'p1_2'}
for n in h['nodes']: n['parts'] = [p for p in n['parts'] if p['meshpartid'] in keep]
h['nodes'] = [n for n in h['nodes'] if n['parts']]
h['meshes'] = meshes; h['id'] = 'tower_door'
json.dump(h, open(os.path.join(ROOT, 'assets', 'pack', 'tower_door.g3dj'), 'w'), separators=(',', ':'))
print('ok', len(meshes), 'meshes')
