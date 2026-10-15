#!/usr/bin/env python3
"""Remove the hamster's remaining paw stub (every triangle skinned to a LowerArm bone) and cap the opening it leaves on the upper arm.

Why: HeroRig draws a ball at each hand, but the shipped mesh still carried 311 triangles per side of the original paw cup
(skinned to LowerArm.L/R, x 1.20-1.29 in model units, including the strip cut's cap). On the rope the arm stretch scaled that
stub about four times and it showed as a second, white hand beside each ball. The earlier strip cut by position along the
elbow->hand axis and left the stub's root; this removes by bone weight + connectivity instead.

What this does (deterministic, JSON in / JSON out):
  * drops every triangle that has a corner whose dominant skin weight is LowerArm.L or LowerArm.R (the whole stub, the
    mixed LowerArm/UpperArm boundary triangles and the fill cap), nothing else;
  * finds the boundary loop(s) that this removal newly opens (edges that were shared by two triangles before and by one
    after; the mesh's pre-existing open edges are left exactly as they were) and closes each with a fan to one new centre
    vertex (mean position/normal/uv of the loop, skin weights copied from the loop vertex nearest the centre - UpperArm);
  * leaves vertices, weights, bones, animations, materials and textures untouched (the stub's vertices stay in the vertex
    array, unreferenced).
usage (repo root):  python3 tools/hamster/remove_paw_stub.py [assets/hero/hamster.g3dj]
"""
import json, sys
import numpy as np
from collections import Counter, defaultdict

PATH = sys.argv[1] if len(sys.argv) > 1 else 'assets/hero/hamster.g3dj'
SIZES = {'POSITION': 3, 'NORMAL': 3, 'TEXCOORD0': 2, 'BLENDWEIGHT0': 2, 'BLENDWEIGHT1': 2, 'BLENDWEIGHT2': 2, 'BLENDWEIGHT3': 2}
FAN_FLIP = True      # same cap winding as tools/hamster/fill_holes.py (cap faces outward)

d = json.load(open(PATH)); m = d['meshes'][0]
attrs = m['attributes']; stride = sum(SIZES[a] for a in attrs)
off = {}; o = 0
for a in attrs: off[a] = o; o += SIZES[a]
V = np.array(m['vertices'], float).reshape(-1, stride)
bw = [a for a in attrs if a.startswith('BLENDWEIGHT')]
def find_part(ns):
    for x in ns:
        for pp in x.get('parts', []):
            if 'bones' in pp: return pp
        if 'children' in x:
            r = find_part(x['children'])
            if r: return r
part = find_part(d['nodes']); bone_names = [b['node'] for b in part['bones']]
idx = np.stack([V[:, off[a]] for a in bw], 1).astype(int); wts = np.stack([V[:, off[a] + 1] for a in bw], 1)
dom = np.array([bone_names[i] for i in idx[np.arange(len(V)), wts.argmax(1)]])
is_stub = np.array(['LowerArm' in n for n in dom])

tris = np.array(m['parts'][0]['indices'], int).reshape(-1, 3)
keep = [t for t in tris if not any(is_stub[i] for i in t)]
removed = len(tris) - len(keep)

# the mesh has unwelded seams (several vertex indices per position), so edges are matched on welded positions, as fill_holes.py does
wkey = [tuple(np.round(p, 3)) for p in V[:, :3]]; wid = {}; welded = np.array([wid.setdefault(k, len(wid)) for k in wkey])
def edge_counts(tl):
    c = Counter()
    for t in tl:
        for a, b in ((t[0], t[1]), (t[1], t[2]), (t[2], t[0])): c[tuple(sorted((int(welded[a]), int(welded[b]))))] += 1
    return c
before, after = edge_counts(tris), edge_counts(keep)
new_open = {e for e, c in after.items() if c == 1 and before[e] == 2}
# directed boundary edges (welded a -> welded b as the kept triangle walks them, with the original vertex pair), newly opened only
nxt = {}
for t in keep:
    for a, b in ((t[0], t[1]), (t[1], t[2]), (t[2], t[0])):
        wa, wb = int(welded[a]), int(welded[b])
        if tuple(sorted((wa, wb))) in new_open: nxt[wa] = (wb, int(a), int(b))
loops = []; used = set()
for start in list(nxt):
    if start in used: continue
    loop = []; cur = start
    while cur in nxt and cur not in used:
        used.add(cur); wb, a, b = nxt[cur]; loop.append((a, b)); cur = wb
    if cur == start and len(loop) >= 3: loops.append(loop)
print(f'{PATH}: removed {removed} stub triangles; newly opened edges {len(new_open)}; loops {[len(l) for l in loops]}')
# the stub is two welded shells per side (the paw cup and a small inner piece), so up to two rims per side open
assert 1 <= len(loops) <= 4 and sum(len(l) for l in loops) == len(new_open), 'unexpected opening topology'

# The rim vertices come from several texture islands (their UVs span most of the atlas), so a fan that reuses them would sweep its
# texture lookup across the atlas and draw dark bands. The cap therefore gets its OWN vertices: the rim vertices duplicated
# (same position, normal and skin weights) and the centre, all with one fur texel's UV - a flat, arm-coloured end.
CAP_UV = (0.903, 0.581)     # light fur texel on the upper arm (255, 227, 217)
new_verts = []; new_tris = []
for loop in loops:
    ring = np.array([a for (a, b) in loop])
    centre = V[ring].mean(0).copy()
    n = centre[off['NORMAL']:off['NORMAL'] + 3]; ln = np.linalg.norm(n); centre[off['NORMAL']:off['NORMAL'] + 3] = n / ln if ln > 1e-6 else (0, 0, 1)
    nearest = ring[np.argmin(np.linalg.norm(V[ring][:, :3] - centre[:3], axis=1))]
    centre[off[bw[0]]:] = V[nearest][off[bw[0]]:]          # skin like the nearest loop vertex (UpperArm)
    centre[off['TEXCOORD0']:off['TEXCOORD0'] + 2] = CAP_UV
    ci = len(V) + len(new_verts); new_verts.append(centre)
    dup = {}                                                # one duplicate per WELDED rim position (seam duplicates share it)
    for (a, b) in loop:
        for v in (a, b):
            w = int(welded[v])
            if w not in dup:
                c = V[v].copy(); c[off['TEXCOORD0']:off['TEXCOORD0'] + 2] = CAP_UV
                dup[w] = len(V) + len(new_verts); new_verts.append(c)
    for (a, b) in loop:                                     # a->b is the kept triangle's direction along the rim
        wa, wb = int(welded[a]), int(welded[b])
        new_tris += ([ci, dup[wb], dup[wa]] if FAN_FLIP else [ci, dup[wa], dup[wb]])
    print(f'  capped loop of {len(loop)} edges at x={centre[0]:.3f} y={centre[1]:.3f} z={centre[2]:.3f}, skin bone {dom[nearest]}')
V2 = np.vstack([V] + ([np.array(new_verts)] if new_verts else []))
m['vertices'] = [round(float(x), 6) for x in V2.reshape(-1)]
m['parts'][0]['indices'] = [int(i) for t in keep for i in t] + [int(i) for i in new_tris]
left = sum(1 for t in keep if any(is_stub[i] for i in t))
assert left == 0
print(f'triangles {len(tris)} -> {len(keep) + len(new_tris) // 3}; vertices {len(V)} -> {len(V2)}; LowerArm-skinned triangles left: 0')
json.dump(d, open(PATH, 'w'), separators=(',', ':'))
