"""Cap the open boundary loops in assets/hero/hamster.g3dj in place, so the model renders solid single-sided.

Why: the supplied hamster mesh has open shells - its cup-shaped paws (and the wrist left open by the earlier hand strip) -
so the sky shows straight through them from the game camera. Rendering double-sided hides the sky but leaves z-fighting
"weird lines" at the thin rims. Instead we close each open boundary loop with a small triangle fan to a central vertex, so
the mesh is watertight there and renders cleanly single-sided.

Each boundary loop (edges used by only one triangle) gets one new centre vertex (averaged position / normal / uv, and the
skin weights of a loop vertex so the cap moves with the same bone) and a fan of triangles closing it. Winding is chosen so
the cap faces outward (flip FAN_FLIP if a cap renders inside-out).

usage:  python3 fill_holes.py <hamster.g3dj>   (edits the file in place)
"""
import json, sys, numpy as np

FAN_FLIP = True
path = sys.argv[1] if len(sys.argv) > 1 else 'assets/hero/hamster.g3dj'
d = json.load(open(path)); m = d['meshes'][0]
attrs = m['attributes']; sizes = {'POSITION':3,'NORMAL':3,'TEXCOORD0':2,'BLENDWEIGHT0':2,'BLENDWEIGHT1':2,'BLENDWEIGHT2':2,'BLENDWEIGHT3':2}
stride = sum(sizes[a] for a in attrs)
V = np.array(m['vertices'], float).reshape(-1, stride)
tris = m['parts'][0]['indices']
pos = V[:, 0:3]

# weld vertices by rounded position
key = [tuple(np.round(p, 3)) for p in pos]
wid = {}; welded = []
for k in key: welded.append(wid.setdefault(k, len(wid)))
welded = np.array(welded)

# directed welded edges -> an original (a,b) that produced it; boundary = reverse missing
dir_edge = {}
for t in range(0, len(tris), 3):
    i = [tris[t], tris[t+1], tris[t+2]]
    for e in ((0,1),(1,2),(2,0)):
        a, b = i[e[0]], i[e[1]]
        dir_edge[(welded[a], welded[b])] = (a, b)
boundary = {}                                   # welded_start -> (welded_end, orig_a, orig_b)
for (wa, wb), (a, b) in dir_edge.items():
    if (wb, wa) not in dir_edge:
        boundary[wa] = (wb, a, b)
print('boundary edges:', len(boundary))

new_verts = []; new_tris = []; nloops = 0; base = len(V)
used = set()
for start in list(boundary):
    if start in used: continue
    loop = []; cur = start
    ok = True
    while True:
        if cur not in boundary or cur in used: ok = (cur == start); break
        used.add(cur); wb, a, b = boundary[cur]; loop.append((a, b)); cur = wb
        if cur == start: break
        if len(loop) > 5000: ok = False; break
    if not ok or len(loop) < 3: continue
    ring = [a for (a, b) in loop]               # ordered original vertices around the hole
    centre = V[ring].mean(0).copy()
    n = centre[3:6]; ln = np.linalg.norm(n); centre[3:6] = n/ln if ln > 1e-6 else (0,0,1)
    centre[8:] = V[ring[0]][8:]                  # same skin weights as a loop vertex
    ci = base + len(new_verts); new_verts.append(centre)
    for (a, b) in loop:
        new_tris += ([ci, b, a] if FAN_FLIP else [ci, a, b])
    nloops += 1
print('filled', nloops, 'loops;', len(new_verts), 'centre verts,', len(new_tris)//3, 'tris')

V2 = np.vstack([V] + ([np.array(new_verts)] if new_verts else []))
m['vertices'] = [round(float(x), 6) for x in V2.reshape(-1)]
m['parts'][0]['indices'] = list(tris) + new_tris
json.dump(d, open(path, 'w'), separators=(',', ':'))
