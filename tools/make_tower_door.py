#!/usr/bin/env python3
"""Gives the Quaternius castle tower (assets/pack/tower.g3dj, CC0) a second door exactly opposite the one it came with -> assets/pack/tower2.g3dj.
The tower wall (mesh part p0_3) has a door notch in the front; the matching notch is cut into the back wall (the plain back wall quads are replaced by the front notch turned
180 degrees about the tower axis, plus two filler quads to the neighbouring wall corners), and the arch frame (p0_0 component), doorway backing (p0_4) and door leaf (p1_*) are copied the same way.
usage: tools/make_tower_door.py"""
import json, copy, os, math, collections
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
g = json.load(open(os.path.join(ROOT, 'assets', 'pack', 'tower.g3dj')))
h = copy.deepcopy(g)
ST = 6                      # POSITION + NORMAL
CZ = -0.046                 # tower axis (x = 0, z = CZ), from the wall's front/back extents (3.32 / -3.40 at x = 0)

def rot(v, tx=0.0, ty=0.0, tz=0.0):
    x, y, z, nx, ny, nz = v
    x += tx; y += ty; z += tz                       # node translation baked in
    return [-x, y, 2 * CZ - z, -nx, ny, -nz]        # a 180 degree turn about the vertical axis keeps the triangle winding

def verts(m): v = m['vertices']; return [v[i:i + ST] for i in range(0, len(v), ST)]
mesh_by_part = {m['parts'][0]['id']: m for m in h['meshes']}
node0 = next(n for n in h['nodes'] if n['id'] == 'n0'); node1 = next(n for n in h['nodes'] if n['id'] == 'n1')
t1 = node1.get('translation', [0, 0, 0])

# 1) wall: cut the same door notch into the back wall
m = mesh_by_part['p0_3']; V = verts(m); part = m['parts'][0]
idx = part['indices']; tris = [idx[i:i + 3] for i in range(0, len(idx), 3)]
front = [t for t in tris if all(V[k][2] > 1 and abs(V[k][0]) <= 2.01 and 1.4 < V[k][1] < 8.1 for k in t)]
back = [t for t in tris if all(V[k][2] < -1 and abs(V[k][0]) <= 2.53 and 1.4 < V[k][1] < 8.1 for k in t)]
assert front and back, (len(front), len(back))
newv = []; newidx = []; base = len(V)
def add(v): newv.append(v); return base + len(newv) - 1
for t in front: newidx += [add(rot(V[k])) for k in t]
YLO, YHI = 1.52, 8.06
for sd in (-1, 1):                                  # filler quads from the turned notch edge (|x| = 2.0) out to the old wall corner (|x| = 2.52)
    p1 = (sd * 2.0, 2 * CZ - 2.75); p2 = (sd * 2.52, -2.27)
    mx, mz = (p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2; L = math.hypot(mx, mz - CZ); n = (mx / L, 0.0, (mz - CZ) / L)
    quad = [(p1, YLO), (p2, YLO), (p2, YHI), (p1, YHI)]
    vs = [add([q[0][0], q[1], q[0][1], n[0], n[1], n[2]]) for q in quad]
    for tri in ((0, 1, 2), (0, 2, 3)):
        a, b, c = [newv[vs[i] - base] for i in tri]
        e1 = [b[i] - a[i] for i in range(3)]; e2 = [c[i] - a[i] for i in range(3)]
        gn = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
        newidx += [vs[i] for i in (tri if gn[0] * n[0] + gn[2] * n[2] > 0 else (tri[0], tri[2], tri[1]))]
m['vertices'] = [c for v in V + newv for c in v]
part['indices'] = [k for t in tris if t not in back for k in t] + newidx

# 2) arch frame: the one connected piece of p0_0 that stands above the base ring on the front
m = mesh_by_part['p0_0']; V = verts(m); part = m['parts'][0]
idx = part['indices']; tris = [idx[i:i + 3] for i in range(0, len(idx), 3)]
key = lambda k: tuple(round(c, 3) for c in V[k][:3])
par = {}
def find(a):
    while par.setdefault(a, a) != a: par[a] = par[par[a]]; a = par[a]
    return a
for t in tris:
    for k in t[1:]: par[find(key(t[0]))] = find(key(k))
comps = collections.defaultdict(list)
for t in tris: comps[find(key(t[0]))].append(t)
arch = [ts for ts in comps.values() if min(V[k][1] for t in ts for k in t) >= 1.3 and all(V[k][2] > 0 and abs(V[k][0]) < 2.0 for t in ts for k in t)]
arch.sort(key=len, reverse=True)      # the arch itself is the big piece; a couple of tiny stray bits near it are ignored
base = len(V); newv = []; newidx = []
for t in arch[0]: newidx += [add(rot(V[k])) for k in t]
m['vertices'] = [c for v in V + newv for c in v]
part['indices'] = idx + newidx

# 3) doorway backing and door leaf
def clone_part(src_pid, new_pid, tx=0.0, ty=0.0, tz=0.0):
    mm = copy.deepcopy(mesh_by_part[src_pid]); mm['vertices'] = [c for v in verts(mm) for c in rot(v, tx, ty, tz)]
    mm['parts'][0]['id'] = new_pid; h['meshes'].append(mm)
    src = next(p for n in h['nodes'] for p in n['parts'] if p['meshpartid'] == src_pid)
    node0['parts'].append({'meshpartid': new_pid, 'materialid': src['materialid']})
clone_part('p0_4', 'p0_4b')
for pid in ('p1_0', 'p1_1', 'p1_2'): clone_part(pid, pid + 'b', *t1)

h['id'] = 'tower2'
json.dump(h, open(os.path.join(ROOT, 'assets', 'pack', 'tower2.g3dj'), 'w'), separators=(',', ':'))
print('ok')
