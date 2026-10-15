"""Re-rigs the hamster onto the bunny's skeleton (owner, Build 63: "make the skeleton and rigging the same in the hamster as it is in the bunny ...
resize and rerig the hamster to match the bunny ... just use the bunny animations").

Input: the shipped skinned hamster (`assets/hero/hamster.g3dj`: the hamster mesh bound to a hamster-shaped copy of the Quaternius skeleton) and
the bunny (`assets/hero/hero.g3dj`). Output: a hamster whose skeleton IS the bunny's - its node tree, its bind pose (the part `bones`), all 18
clips copied verbatim - and whose mesh is the hamster's, moved region by region from the hamster's bind pose into the bunny's:

    v' = sum_i w_i * M_bone(i) * v        (w: the vertex's skin weights, kept as they are; normals through the inverse transpose)

with one affine map per bone, chosen so the parts fit the bunny's joints and nothing tears:
  * arms: each arm bone is stretched from the hamster's joint to its child joint onto the bunny's (the same joints, so the bunny's clips swing the
    arm exactly as they swing the bunny's), thickened across by LAT_UPPER / LAT_LOWER so the stub does not become a stick;
  * legs: the thigh likewise between hip and knee; the shin and foot are scaled straight down from the knee so the sole stays flat on the ground;
  * spine (Body, Hips, Abdomen, Torso, Neck): ONE map for the whole chain (no stretching between the vertebrae), narrowed to LAT_BODY and scaled
    about the hips so the hamster's shoulders land at the height of the bunny's arm roots: the body stays one coherent shape and the arms root in it;
  * head and ears: rigid with the head, moved to the bunny's head joint. The hamster keeps its own small ears: the ear bones alone keep the hamster's
    joint offsets (the clips' ear translation keys, which are constant, are rewritten to them), so the ears still wiggle at their own base.
The texture, UVs, material and skin weights are untouched. The arm bones end up as long as the bunny's, so HeroRig stops taking its stub-arm paths
(it measures the arm length). Deterministic JSON in / JSON out; prints what it measured; refuses to write when a premise fails.

usage:  python3 tools/hamster/retarget_to_bunny.py [--analyze] [--src tools/hamster/hamster_stub_rig.g3dj] [--ref assets/hero/hero.g3dj] [--out assets/hero/hamster.g3dj]
"""
import argparse, json, math
import numpy as np

LAT_BODY = 0.66     # body width and depth, hamster -> bunny (the bunny's body is about two thirds as wide as the hamster's)
LAT_UPPER = 1.3     # upper arm thickness across the bone (the stub is stretched 2.3x along it)
LAT_LOWER = 1.6     # lower arm thickness across the bone (stretched 1.9x along it); the fists are HeroRig's ball hands
LAT_LEG = 0.85      # thighs, shins and feet across the bone
HEAD_SCALE = 1.0    # the head and ears, about the head joint

SPINE = ('Body', 'Hips', 'Abdomen', 'Torso', 'Neck')
HEADS = ('Head', 'Ear1.L', 'Ear2.L', 'Ear3.L', 'Ear1.R', 'Ear2.R', 'Ear3.R')
EARS = HEADS[1:]

# ------------------------------------------------------------------ maths
def quat_to_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]], dtype=np.float64)

def mat_to_quat(m):
    t = np.trace(m)
    if t > 0:
        s = math.sqrt(t + 1) * 2; w = 0.25 * s; x = (m[2, 1] - m[1, 2]) / s; y = (m[0, 2] - m[2, 0]) / s; z = (m[1, 0] - m[0, 1]) / s
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = math.sqrt(1 + m[0, 0] - m[1, 1] - m[2, 2]) * 2; w = (m[2, 1] - m[1, 2]) / s; x = 0.25 * s; y = (m[0, 1] + m[1, 0]) / s; z = (m[0, 2] + m[2, 0]) / s
    elif m[1, 1] > m[2, 2]:
        s = math.sqrt(1 + m[1, 1] - m[0, 0] - m[2, 2]) * 2; w = (m[0, 2] - m[2, 0]) / s; x = (m[0, 1] + m[1, 0]) / s; y = 0.25 * s; z = (m[1, 2] + m[2, 1]) / s
    else:
        s = math.sqrt(1 + m[2, 2] - m[0, 0] - m[1, 1]) * 2; w = (m[1, 0] - m[0, 1]) / s; x = (m[0, 2] + m[2, 0]) / s; y = (m[1, 2] + m[2, 1]) / s; z = 0.25 * s
    return [x, y, z, w]

def trs(t, r, s):
    m = np.eye(4); m[:3, :3] = quat_to_mat(r) @ np.diag(s); m[:3, 3] = t
    return m

def T(v):
    m = np.eye(4); m[:3, 3] = v; return m

def rot_between(a, b):
    """Smallest rotation taking unit vector a onto unit vector b."""
    a = a / np.linalg.norm(a); b = b / np.linalg.norm(b)
    v = np.cross(a, b); c = float(np.dot(a, b))
    if np.linalg.norm(v) < 1e-9: return np.eye(3) if c > 0 else -np.eye(3)
    K = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + K + K @ K * (1 / (1 + c))

def stretch_map(p_h, c_h, p_b, c_b, lat):
    """Affine map (model space) taking the hamster's bone (joint p_h, child joint c_h) onto the bunny's (p_b, c_b): along the bone by the length
    ratio, across it by lat, turned so the bone points the bunny's way."""
    u_h = c_h - p_h; u_b = c_b - p_b
    sy = np.linalg.norm(u_b) / np.linalg.norm(u_h)
    uh = u_h / np.linalg.norm(u_h)
    A = rot_between(uh, u_b) @ (lat * np.eye(3) + (sy - lat) * np.outer(uh, uh))
    m = np.eye(4); m[:3, :3] = A; m[:3, 3] = p_b - A @ p_h
    return m

def scale_about(p_h, p_b, sx, sy, sz):
    m = np.eye(4); m[:3, :3] = np.diag([sx, sy, sz]); m[:3, 3] = p_b - m[:3, :3] @ p_h
    return m

def node_local(n):
    return trs(n.get('translation', [0, 0, 0]), n.get('rotation', [0, 0, 0, 1]), n.get('scale', [1, 1, 1]))

def bone_name(node_id):
    """'n12_Shoulder.L' -> 'Shoulder.L' (the numbering prefix is per export)."""
    return node_id.split('_', 1)[1] if '_' in node_id else node_id

# ------------------------------------------------------------------ model access
def nodes_by_id(model):
    out, parent = {}, {}
    def walk(n, p):
        out[n['id']] = n; parent[n['id']] = p
        for c in n.get('children', []): walk(c, n['id'])
    for n in model['nodes']: walk(n, None)
    return out, parent

def global_binds(model):
    """Global bind transform per bone: the part `bones` entries (what libGDX inverts for skinning). The node tree may hold another rest pose
    (the bunny's tree has its arms down while its skin is bound in a T-pose); the clips key every bone, so that only shows while nothing plays."""
    byid, parent = nodes_by_id(model)
    binds = {}
    for n in byid.values():
        for part in n.get('parts', []):
            for b in part.get('bones', []):
                binds[b['node']] = trs(b['translation'], b['rotation'], b.get('scale', [1, 1, 1]))
    if not binds: raise SystemExit('no skinned part (no bind pose) in %s' % model.get('id'))
    cache = {}
    def g(i):
        if i in cache: return cache[i]
        m = node_local(byid[i]); p = parent[i]
        cache[i] = (g(p) @ m) if p is not None else m
        return cache[i]
    worst = max(float(np.abs(g(i) - m).max()) for i, m in binds.items())
    if worst > 2e-3: print('note: %s: node tree rest pose differs from the bind pose by up to %.3f; the bind pose is used' % (model.get('id'), worst))
    return binds

def skin(model):
    """Every skinned part: (mesh index, mesh, attributes, offsets, vertices (n, stride), part, mesh node, node part)."""
    byid, _ = nodes_by_id(model)
    out = []
    for mi, mesh in enumerate(model['meshes']):
        attrs = mesh['attributes']; off = {}; k = 0
        for a in attrs:
            off[a] = k; k += 2 if a.startswith('TEXCOORD') or a.startswith('BLENDWEIGHT') else 3
        v = np.array(mesh['vertices'], dtype=np.float64).reshape(-1, k)
        for part in mesh['parts']:
            for n in byid.values():
                for np_ in n.get('parts', []):
                    if np_['meshpartid'] == part['id'] and np_.get('bones'): out.append((mi, mesh, attrs, off, v, part, n, np_))
    return out

def dominant_bone(v, off):
    ws = [a for a in off if a.startswith('BLENDWEIGHT')]
    idx = np.stack([v[:, off[a]] for a in ws], 1).astype(int); wt = np.stack([v[:, off[a] + 1] for a in ws], 1)
    return idx[np.arange(len(v)), wt.argmax(1)]

def regions(model):
    """Per bone name: the global bbox of the vertices whose heaviest weight is that bone."""
    res = {}
    for (_, mesh, attrs, off, v, part, node, npart) in skin(model):
        order = [b['node'] for b in npart['bones']]
        dom = dominant_bone(v, off)
        P = v[:, off['POSITION']:off['POSITION'] + 3]
        for bi, bid in enumerate(order):
            sel = P[dom == bi]
            if len(sel) == 0: continue
            e = res.setdefault(bone_name(bid), [np.full(3, 1e9), np.full(3, -1e9), 0])
            e[0] = np.minimum(e[0], sel.min(0)); e[1] = np.maximum(e[1], sel.max(0)); e[2] += len(sel)
    return res

# ------------------------------------------------------------------ the retarget
def joints(binds):
    return {bone_name(k): v[:3, 3].copy() for k, v in binds.items()}

def bone_maps(jh, jb):
    """One 4x4 model-space map per bone name."""
    maps = {}
    # the spine: one map for the chain, narrowed, scaled about the hips so the shoulders land at the bunny's arm-root height
    sy = (jb['UpperArm.L'][1] - jb['Hips'][1]) / (jh['Shoulder.L'][1] - jh['Hips'][1])
    spine = scale_about(jh['Hips'], jb['Hips'], LAT_BODY, sy, LAT_BODY)
    for b in SPINE: maps[b] = spine
    # head and ears: rigid with the head (uniform HEAD_SCALE about the head joint)
    head = scale_about(jh['Head'], jb['Head'], HEAD_SCALE, HEAD_SCALE, HEAD_SCALE)
    for b in HEADS: maps[b] = head
    for side in ('L', 'R'):
        maps['UpperArm.' + side] = stretch_map(jh['UpperArm.' + side], jh['LowerArm.' + side], jb['UpperArm.' + side], jb['LowerArm.' + side], LAT_UPPER)
        maps['LowerArm.' + side] = stretch_map(jh['LowerArm.' + side], jh['Fist.' + side], jb['LowerArm.' + side], jb['Fist.' + side], LAT_LOWER)
        maps['Fist.' + side] = maps['LowerArm.' + side]                    # no hamster vertices (HeroRig draws ball hands); keep the arm's map for continuity
        maps['Shoulder.' + side] = maps['UpperArm.' + side]                 # no hamster vertices
        maps['UpperLeg.' + side] = stretch_map(jh['UpperLeg.' + side], jh['LowerLeg.' + side], jb['UpperLeg.' + side], jb['LowerLeg.' + side], LAT_LEG)
        k = jb['LowerLeg.' + side][1] / jh['LowerLeg.' + side][1]           # shin + foot: straight down from the knee so the sole stays on the ground
        maps['LowerLeg.' + side] = scale_about(jh['LowerLeg.' + side], jb['LowerLeg.' + side], LAT_LEG, k, LAT_LEG)
        maps['Foot.' + side] = maps['LowerLeg.' + side]
        maps['PoleTarget.' + side] = np.eye(4)
    maps['Root'] = np.eye(4)
    return maps, sy

def retarget(ham, bun, verbose=True):
    gh, gb = global_binds(ham), global_binds(bun)
    jh, jb = joints(gh), joints(gb)
    if set(jh) != set(jb): raise SystemExit('the two skeletons differ in bones: %s' % sorted(set(jh) ^ set(jb)))
    maps, sy = bone_maps(jh, jb)
    if set(maps) != set(jh): raise SystemExit('no map for bones %s' % sorted(set(jh) ^ set(maps)))
    skins = skin(ham)
    if len(skins) != 1: raise SystemExit('expected one skinned hamster mesh part, found %d' % len(skins))
    (mi, mesh, attrs, off, v, part, node, npart) = skins[0]
    order = [b['node'] for b in npart['bones']]
    ws = [a for a in attrs if a.startswith('BLENDWEIGHT')]
    P = v[:, off['POSITION']:off['POSITION'] + 3]; N = v[:, off['NORMAL']:off['NORMAL'] + 3]
    newP = np.zeros_like(P); newN = np.zeros_like(N); wsum = np.zeros(len(v))
    for a in ws:
        idx = v[:, off[a]].astype(int); wt = v[:, off[a] + 1]
        for bi, nid in enumerate(order):
            sel = np.where((idx == bi) & (wt > 0))[0]
            if len(sel) == 0: continue
            M = maps[bone_name(nid)]; L = M[:3, :3]; LN = np.linalg.inv(L).T
            newP[sel] += wt[sel, None] * ((L @ P[sel].T).T + M[:3, 3])
            newN[sel] += wt[sel, None] * (LN @ N[sel].T).T
            wsum[sel] += wt[sel]
    if np.abs(wsum - 1).max() > 1e-3: raise SystemExit('skin weights do not sum to one (max deviation %.4f)' % np.abs(wsum - 1).max())
    nl = np.linalg.norm(newN, axis=1); nl[nl < 1e-9] = 1; newN /= nl[:, None]
    if not np.isfinite(newP).all(): raise SystemExit('non-finite vertex positions')

    out = json.loads(json.dumps(ham))                     # the hamster's mesh, material, texture ...
    nv = v.copy(); nv[:, off['POSITION']:off['POSITION'] + 3] = newP; nv[:, off['NORMAL']:off['NORMAL'] + 3] = newN
    out['meshes'][mi]['vertices'] = [round(float(x), 5) for x in nv.reshape(-1)]

    # ... on the bunny's skeleton: its node tree, with the ear joints at the hamster's own offsets from the head
    hbyid, hparent = nodes_by_id(ham)
    arm = [n for n in bun['nodes'] if any(c.get('children') for c in n.get('children', []))]
    if len(arm) != 1: raise SystemExit('expected one armature root in the bunny')
    new_arm = json.loads(json.dumps(arm[0]))
    new_arm['children'] = [c for c in new_arm['children'] if not c.get('parts')]
    nbyid, nparent = nodes_by_id({'nodes': [new_arm]})
    ear_local = {}
    for nid, n in nbyid.items():
        b = bone_name(nid)
        if b in EARS:
            hn = [k for k in hbyid if bone_name(k) == b][0]
            t = [round(HEAD_SCALE * x, 5) for x in hbyid[hn]['translation']]
            n['translation'] = t; ear_local[nid] = t
            if np.abs(np.array(hbyid[hn].get('rotation', [0, 0, 0, 1])) - np.array(n.get('rotation', [0, 0, 0, 1]))).max() > 1e-3:
                raise SystemExit('ear bone %s has a different rest rotation in the two rigs' % b)
    # bind pose for the mesh: the bunny's part bones; the ears recomputed from the bunny's head bind and the hamster's ear offsets
    bun_part_bones = None
    for n in nodes_by_id(bun)[0].values():
        for p in n.get('parts', []):
            if p.get('bones'): bun_part_bones = {b['node']: b for b in p['bones']}
    new_binds = dict(gb)
    for nid in [k for k in nbyid if bone_name(k) in EARS]:
        chain = []; k = nid
        while bone_name(k) in EARS: chain.append(k); k = nparent[k]
        m = new_binds[k]                                              # the head's bind (the bunny's)
        for e in reversed(chain): m = m @ node_local(nbyid[e])
        new_binds[nid] = m
    new_mesh_node = json.loads(json.dumps(node))
    for p in new_mesh_node['parts']:
        entries = []
        for b in p['bones']:
            m = new_binds[b['node']]
            entries.append({'node': b['node'], 'translation': [round(float(x), 5) for x in m[:3, 3]], 'rotation': [round(float(x), 5) for x in mat_to_quat(m[:3, :3])], 'scale': [1.0, 1.0, 1.0]})
            if bone_name(b['node']) not in EARS:                      # must be the bunny's bind exactly
                e = bun_part_bones[b['node']]
                if np.abs(np.array(e['translation']) - m[:3, 3]).max() > 1e-4: raise SystemExit('bind drift on %s' % b['node'])
                entries[-1] = dict(e)
        p['bones'] = entries
    new_arm['children'].insert(0, new_mesh_node)
    out['nodes'] = [new_arm]
    out['animations'] = json.loads(json.dumps(bun['animations']))
    for a in out['animations']:
        for b in a['bones']:
            if b['boneId'] in ear_local:
                for kf in b['keyframes']:
                    if 'translation' in kf: kf['translation'] = list(ear_local[b['boneId']])
    out['id'] = ham.get('id', 'hamster')

    # what it did
    reg = regions(out)
    if verbose:
        print('spine scaled x%.3f about the hips, body width x%.2f; arms x%.2f/%.2f across, legs x%.2f; head x%.2f' % (sy, LAT_BODY, LAT_UPPER, LAT_LOWER, LAT_LEG, HEAD_SCALE))
        print('%-12s %6s  %-22s %-22s %-22s' % ('region', 'verts', 'y', 'x', 'z'))
        for b in sorted(reg, key=lambda k: (k.split('.')[0], k)):
            lo, hi, n = reg[b]
            print('%-12s %6d  %6.3f..%6.3f %6.3f..%6.3f %6.3f..%6.3f' % (b, n, lo[1], hi[1], lo[0], hi[0], lo[2], hi[2]))
        print('ear joints now', {bone_name(k): [round(float(x), 3) for x in new_binds[k][:3, 3]] for k in new_binds if bone_name(k) in ('Ear1.L', 'Ear2.L', 'Ear3.L')})
    # premise checks on the result
    span = newP.max(0) - newP.min(0)
    sole = newP[:, 1].min()
    if not (2.5 < span[1] < 5.0): raise SystemExit('result height %.3f is not a character' % span[1])
    if abs(sole) > 0.05: raise SystemExit('the soles are at y=%.3f, not on the ground' % sole)
    for side in ('L', 'R'):
        lo, hi, n = reg['LowerArm.' + side]
        reach = max(abs(lo[0]), abs(hi[0]))
        if reach < 0.95 * abs(jb['Fist.' + side][0]): raise SystemExit('the %s arm does not reach the bunny fist joint (%.3f < %.3f)' % (side, reach, jb['Fist.' + side][0]))
    print('result: height %.3f, width %.3f, depth %.3f, soles at y=%.3f' % (span[1], span[0], span[2], sole))
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', default='tools/hamster/hamster_stub_rig.g3dj'); ap.add_argument('--ref', default='assets/hero/hero.g3dj'); ap.add_argument('--out', default='assets/hero/hamster.g3dj')
    ap.add_argument('--analyze', action='store_true', help='print the measurements, write nothing')
    a = ap.parse_args()
    ham = json.load(open(a.src)); bun = json.load(open(a.ref))
    out = retarget(ham, bun)
    if a.analyze: return
    with open(a.out, 'w') as f: json.dump(out, f, separators=(',', ':'))
    print('wrote', a.out)

if __name__ == '__main__':
    main()
