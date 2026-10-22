#!/usr/bin/env python3
"""Re-base the hamster's arm-bone rest frames so the bunny's animation clips drive them as they drive the bunny.

Why: build_rigged_hamster.py keeps every bone's LOCAL rest rotation from the bunny skeleton (hero.g3dj) but moves the joints
to hamster landmarks. The arm joints were laid out as a horizontal chain, so each arm bone's child no longer sits along the
bone's local +Y axis (it is 42-58 degrees off). The clips' rotation keys - absolute local rotations authored for a bone whose
child lies on +Y - therefore swing the hamster's arms around a horizontal rest instead of a hanging one: arms straight out
while running, jumping, falling and leaving a rope, and the rope stretch (which lengthens local Y) pulling across the arm.

What this does (deterministic, JSON in / JSON out, no Blender):
  for Shoulder, UpperArm, LowerArm and Fist on both sides
    new rest GLOBAL rotation = Rw * old global rotation, where Rw is the minimal rotation taking the bunny's rest bone direction
    (joint -> child joint) onto the hamster's; the Fist (no child) takes its LowerArm's Rw. Every joint position is kept exactly.
  for every node: local rest rotation and translation re-expressed in its parent's (possibly new) frame
  bind matrices (the skinned part's bones[]) rewritten from the new globals
  every clip: the TRANSLATION keys of nodes whose parent frame changed are re-expressed in the new parent frame
Rotation keys, vertices, skin weights, indices, materials and textures are untouched. Result: each arm bone's local +Y now
points at its child exactly as in the bunny, so the bunny's keys give the bunny's world arm directions on the hamster.

usage (repo root):  python3 tools/hamster/rebase_arm_frames.py [assets/hero/hamster.g3dj] [assets/hero/hero.g3dj]
The script prints the local child direction of every arm bone before and after (after: (0, 1, 0)) and refuses to write if
any joint position would move.
"""
import json, sys, math
import numpy as np

HAM = sys.argv[1] if len(sys.argv) > 1 else 'assets/hero/hamster.g3dj'
BUN = sys.argv[2] if len(sys.argv) > 2 else 'assets/hero/hero.g3dj'
ARM = ['Shoulder', 'UpperArm', 'LowerArm', 'Fist']
CHILD = {'Shoulder': 'UpperArm', 'UpperArm': 'LowerArm', 'LowerArm': 'Fist'}

# ---- quaternions [x, y, z, w]; q1 * q2 applies q2 first, then q1 (matches a parent*local hierarchy)
def qmul(a, b):
    x1, y1, z1, w1 = a; x2, y2, z2, w2 = b
    return np.array([w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2, w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
                     w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2, w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2])
def qconj(q): return np.array([-q[0], -q[1], -q[2], q[3]])
def qrot(q, v):
    u = np.array(q[:3]); w = q[3]; v = np.asarray(v, float)
    return v + 2.0 * np.cross(u, np.cross(u, v) + w * v)
def qnorm(q): q = np.asarray(q, float); return q / np.linalg.norm(q)
def from_to(a, b):
    """Minimal rotation taking unit vector a onto unit vector b."""
    a = np.asarray(a, float) / np.linalg.norm(a); b = np.asarray(b, float) / np.linalg.norm(b)
    axis = np.cross(a, b); s = np.linalg.norm(axis); c = float(np.dot(a, b))
    if s < 1e-9:
        if c > 0: return np.array([0.0, 0.0, 0.0, 1.0])
        p = np.cross(a, [1, 0, 0]);
        if np.linalg.norm(p) < 1e-6: p = np.cross(a, [0, 1, 0])
        p /= np.linalg.norm(p); return np.array([p[0], p[1], p[2], 0.0])
    th = math.atan2(s, c); axis /= s
    return np.array([*(axis * math.sin(th / 2)), math.cos(th / 2)])
def short(i): return i.split('_', 1)[1] if '_' in i else i

def load(path):
    d = json.load(open(path))
    nodes = {}; order = []
    def walk(n, parent):
        nodes[n['id']] = dict(node=n, parent=parent); order.append(n['id'])
        for c in n.get('children', []): walk(c, n['id'])
    for n in d['nodes']: walk(n, None)
    return d, nodes, order
def rest_globals(nodes, order):
    G = {}
    for i in order:
        n = nodes[i]['node']; p = nodes[i]['parent']
        t = np.array(n.get('translation', [0, 0, 0]), float); q = qnorm(n.get('rotation', [0, 0, 0, 1])); s = n.get('scale', [1, 1, 1])
        assert all(abs(x - 1) < 1e-6 for x in s), f'{i}: non-unit rest scale {s}'
        if p is None: G[i] = (t, q)
        else: pt, pq = G[p]; G[i] = (pt + qrot(pq, t), qmul(pq, q))
    return G

ham, hn, horder = load(HAM); bun, bn, border = load(BUN)
hG = rest_globals(hn, horder); bG = rest_globals(bn, border)
hby = {short(i): i for i in hn}; bby = {short(i): i for i in bn}

# ---- the per-bone world correction Rw
Rw = {}
for side in ('.L', '.R'):
    for b in ARM:
        name = b + side; hid = hby[name]; bid = bby[name]
        if b in CHILD:
            hc = hby[CHILD[b] + side]; bc = bby[CHILD[b] + side]
            dh = hG[hc][0] - hG[hid][0]; db = bG[bc][0] - bG[bid][0]
            Rw[hid] = from_to(db, dh)
        else:
            Rw[hid] = Rw[hby['LowerArm' + side]]
        # sanity: the hamster kept the bunny's global rest rotation (the premise of this re-base)
        dq = qmul(qconj(hG[hid][1]), bG[bid][1]); ang = 2 * math.degrees(math.acos(min(1.0, abs(dq[3]))))
        assert ang < 0.5, f'{name}: hamster rest global differs from the bunny by {ang:.2f} deg; premise broken'

# ---- new globals: positions unchanged, rotations corrected for the arm bones
newG = {}
for i in horder:
    t, q = hG[i]
    newG[i] = (t.copy(), qnorm(qmul(Rw[i], q)) if i in Rw else q.copy())

def local_dir(i, child):
    """Direction of the child joint in bone i's own frame (rest)."""
    t, q = newG[i]; ct, _ = newG[child]; d = ct - t; d /= np.linalg.norm(d)
    return qrot(qconj(q), d)
print('arm bone local child direction (bone frame), BEFORE -> AFTER:')
for side in ('.L', '.R'):
    for b in ARM:
        if b not in CHILD: continue
        i = hby[b + side]; c = hby[CHILD[b] + side]
        tq = hG[i]; d = hG[c][0] - tq[0]; d /= np.linalg.norm(d); before = qrot(qconj(tq[1]), d)
        print(f'  {b + side:12s} {np.round(before, 3)} -> {np.round(local_dir(i, c), 3)}   Rw angle {2 * math.degrees(math.acos(min(1, abs(Rw[i][3])))):.1f} deg')

# ---- re-express every node's local TRS in its parent's new frame (positions must not move)
oldLocalT = {}; changedT = set(); changedParentFrame = set()
for i in horder:
    n = hn[i]['node']; p = hn[i]['parent']
    oldLocalT[i] = np.array(n.get('translation', [0, 0, 0]), float)
    if p is None: continue
    pt, pq = newG[p]; t, q = newG[i]
    newT = qrot(qconj(pq), t - pt); newQ = qnorm(qmul(qconj(pq), q))
    # check that the recomposed global reproduces the stored position exactly
    chk = pt + qrot(pq, newT); assert np.linalg.norm(chk - t) < 1e-6, i
    oldT = oldLocalT[i]; oldQ = qnorm(n.get('rotation', [0, 0, 0, 1]))
    if np.linalg.norm(newT - oldT) > 1e-6: changedT.add(i)
    if p in Rw: changedParentFrame.add(i)
    if 'translation' in n: n['translation'] = [round(float(x), 6) for x in newT]
    if 'rotation' in n: n['rotation'] = [round(float(x), 6) for x in newQ]
    if (i in Rw or p in Rw) and 'rotation' not in n: n['rotation'] = [round(float(x), 6) for x in newQ]
    if (i in Rw or p in Rw) and 'translation' not in n: n['translation'] = [round(float(x), 6) for x in newT]
# nodes outside the arm chain (and not children of it) must be untouched
for i in horder:
    p = hn[i]['parent']
    if i not in Rw and p not in Rw: assert i not in changedT, f'{i}: unexpected translation change'

# ---- bind matrices
def find_part(nodes_list):
    for x in nodes_list:
        for pp in x.get('parts', []):
            if 'bones' in pp: return pp
        if 'children' in x:
            r = find_part(x['children'])
            if r: return r
part = find_part(ham['nodes'])
for b in part['bones']:
    t, q = newG[b['node']]
    assert np.linalg.norm(np.array(b['translation'], float) - t) < 1e-4, f"bind translation mismatch for {b['node']}"
    b['rotation'] = [round(float(x), 6) for x in q]
    b['translation'] = [round(float(x), 6) for x in t]

# ---- animation translation keys of nodes whose parent frame changed (rotation keys untouched)
fixed = 0
for a in ham['animations']:
    for tr in a['bones']:
        i = tr['boneId']
        if i not in changedParentFrame: continue
        p = hn[i]['parent']; adj = qmul(qconj(newG[p][1]), hG[p][1])     # old parent frame -> new parent frame
        newT = np.array(hn[i]['node']['translation'], float)
        for kf in tr['keyframes']:
            if 'translation' in kf:
                delta = np.array(kf['translation'], float) - oldLocalT[i]
                kf['translation'] = [round(float(x), 6) for x in newT + qrot(adj, delta)]; fixed += 1
print(f'translation keys re-expressed: {fixed}; nodes with a new parent frame: {sorted(short(i) for i in changedParentFrame)}')

# ---- final check: rest-pose joint positions unchanged
G2 = rest_globals(hn, horder)
worst = max(np.linalg.norm(G2[i][0] - hG[i][0]) for i in horder)
assert worst < 1e-4, f'joint moved by {worst}'
print(f'joint positions preserved (max drift {worst:.2e}); writing {HAM}')
json.dump(ham, open(HAM, 'w'), separators=(',', ':'))
