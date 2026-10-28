#!/usr/bin/env python3
"""Re-aim the hamster's Run and Walk upper-arm keys so its arms hang beside the torso and swing fore and aft while it runs.

Why: after rebase_arm_frames.py the bunny's clips give the hamster the bunny's WORLD arm directions, exactly. The bunny's running
upper arm points about 43 degrees outward from straight down (its rest upper-arm direction is (0.66, -0.73, -0.19)): on the bunny's
narrow round body that reads as arms hanging beside it. The hamster's upper-arm joints sit at x = +-0.965 on a torso only 0.72-0.87
wide, and its arm bones are short (0.227), so the very same directions read as arms held out to the sides that sway - the
"airplane wings" the owner saw in build 57. Nothing is wrong with the frames any more; the bunny's running pose itself is the wrong
pose for this body.

What this does (deterministic, JSON in / JSON out, no Blender):
  For clips Run and Walk only, every rotation key of UpperArm.L and UpperArm.R is pre-multiplied by one constant rotation C_S,
  expressed in the Shoulder's frame:  C_S = conj(Q_S) * C_world * Q_S
    Q_S     = the Shoulder's global rotation with the torso chain at rest and the clip's Shoulder key (in these two clips the
              Shoulder holds one constant rotation, so C_S is a constant in the torso frame; after the re-base the hamster's
              animated Shoulder rotation IS the bunny's);
    C_world = the minimal world rotation taking the bunny's rest upper-arm direction onto (+-sin tilt, -cos tilt, 0): straight
              down with a small outward tilt (left arm +x, right arm -x).
  So the hamster's animated UpperArm global rotation becomes  D(t) * C_world * D(t)^-1 * (bunny's), with D(t) the torso's own
  motion in the clip: the bunny's whole arm motion (fore/aft swing, elbow bend, wrist) is rotated as one rigid thing so the arm
  hangs from the shoulder and follows the torso. Pre-multiplying every key by one unit quaternion commutes with libGDX's key
  slerp, so the in-between frames are retargeted exactly too. LowerArm and Fist keys, every other clip, the rest pose, the bind
  matrices, vertices, weights, materials and textures are untouched (so rope climbing, cable traversal, jumping, falling and
  ledge hanging, which use other clips or code-driven arms, cannot change).
  Each key is also post-multiplied by a constant twist about the upper arm's own axis (which cannot move the upper arm, only where
  the elbow bends): the bunny bends its forearms across its belly, which on the hanging hamster arm would push the hand into the
  torso; the twist (least squares over the cycle, per clip and side) turns the bend into the body's fore/aft plane, pointing forward,
  with the bunny's elbow angles kept exactly.
  The tilt is derived, not chosen: the smallest whole degree for which the drawn hand ball (HeroRig: BALL_RATIO 0.10 world units
  = 0.10 / Characters.scale(1) model units, centred BALL_AT = 60 % of the way from the elbow to the fist) presses into the body
  (every vertex not skinned to the arms, head or ears) by no more than SINK of its radius at any moment of the Run and Walk cycles,
  i.e. a hand resting against the fur, never buried in it. The cycles are evaluated the way libGDX plays them: per-channel key
  lerp/slerp, global = parent * local, linear-blend skinning with the part's bind matrices. The script prints the tilt table, the arm
  directions before and after, and refuses to write if any track other than the four it owns would change.

usage (repo root):  python3 tools/hamster/retarget_run_arms.py [assets/hero/hamster.g3dj] [assets/hero/hero.g3dj] [--tilt DEG] [--dry-run]
Run it after rebase_arm_frames.py and remove_paw_stub.py (see README.md). --tilt forces a tilt instead of deriving it (diagnostics).
"""
import json, sys, math
import numpy as np

args = []; DRY = False; FORCED_TILT = None; it = iter(sys.argv[1:])
for a in it:
    if a == '--dry-run': DRY = True
    elif a == '--tilt': FORCED_TILT = float(next(it))
    else: args.append(a)
HAM = args[0] if len(args) > 0 else 'assets/hero/hamster.g3dj'
BUN = args[1] if len(args) > 1 else 'assets/hero/hero.g3dj'
CLIPS = ('Run', 'Walk')
BALL_R = 0.10 / 0.37               # HeroRig.BALL_RATIO * SCALE / Characters.scale(0) world units -> model units (SCALE = Characters.scale(1) = 0.37)
SINK = 0.25                        # the ball may press into the fur by at most this fraction of its radius (a hand resting against the body):
CLEAR = -SINK * BALL_R             # it is 0.27 model units wide on a 0.227 forearm whose elbow hangs 0.19 outside the torso, so it can only clear
                                   # the body outright when the arm is held out - the very look being removed
BALL_AT = 0.6                      # HeroRig.BALL_AT: ball centre = elbow + 0.6 * (fist - elbow)
SAMPLES = 60                       # evaluation points per clip (keys are ~24 fps; 60 covers every in-between pose)
MAX_TILT = 30

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
        p = np.cross(a, [1, 0, 0])
        if np.linalg.norm(p) < 1e-6: p = np.cross(a, [0, 1, 0])
        p /= np.linalg.norm(p); return np.array([p[0], p[1], p[2], 0.0])
    th = math.atan2(s, c); axis /= s
    return np.array([*(axis * math.sin(th / 2)), math.cos(th / 2)])
def slerp(a, b, t):
    """libGDX Quaternion.slerp: shortest path, linear below 0.1 of cosine distance."""
    d = float(np.dot(a, b)); ad = abs(d); s0, s1 = 1 - t, t
    if 1 - ad > 0.1:
        ang = math.acos(ad); inv = 1 / math.sin(ang); s0 = math.sin((1 - t) * ang) * inv; s1 = math.sin(t * ang) * inv
    if d < 0: s1 = -s1
    return qnorm(s0 * np.asarray(a) + s1 * np.asarray(b))
def qmat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
def short(i): return i.split('_', 1)[1] if '_' in i else i
def angle_deg(q): return 2 * math.degrees(math.acos(min(1.0, abs(float(q[3])))))

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
        t = np.array(n.get('translation', [0, 0, 0]), float); q = qnorm(n.get('rotation', [0, 0, 0, 1]))
        assert all(abs(x - 1) < 1e-6 for x in n.get('scale', [1, 1, 1])), f'{i}: non-unit rest scale'
        if p is None: G[i] = (t, q)
        else: pt, pq = G[p]; G[i] = (pt + qrot(pq, t), qmul(pq, q))
    return G
def find_part(ns):
    for x in ns:
        for pp in x.get('parts', []):
            if 'bones' in pp: return pp
        if 'children' in x:
            r = find_part(x['children'])
            if r: return r

# ---- clip evaluation, as libGDX plays it (AnimationController: per-channel key interpolation, unkeyed channels keep the rest value)
def tracks(clip):
    tr = {}
    for b in clip['bones']:
        T = [(k['keytime'], np.array(k['translation'], float)) for k in b['keyframes'] if 'translation' in k]
        R = [(k['keytime'], qnorm(k['rotation'])) for k in b['keyframes'] if 'rotation' in k]
        assert not any('scale' in k for k in b['keyframes']), f"{clip['id']} {b['boneId']}: scale keys are not handled"
        tr[b['boneId']] = (T, R)
    return tr
def at(keys, t, mix):
    if not keys: return None
    if t <= keys[0][0]: return keys[0][1]
    if t >= keys[-1][0]: return keys[-1][1]
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t0 <= t <= t1:
            a = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return mix(v0, v1, a)
def pose(nodes, order, tr, t):
    """Global (position, rotation) of every node at clip time t (ms)."""
    G = {}
    for i in order:
        n = nodes[i]['node']; p = nodes[i]['parent']
        T, R = tr.get(i, ([], []))
        tt = at(T, t, lambda a, b, s: a + (b - a) * s); rr = at(R, t, slerp)
        lt = np.array(n.get('translation', [0, 0, 0]), float) if tt is None else tt
        lq = qnorm(n.get('rotation', [0, 0, 0, 1])) if rr is None else rr
        if p is None: G[i] = (lt, lq)
        else: pt, pq = G[p]; G[i] = (pt + qrot(pq, lt), qmul(pq, lq))
    return G
def clip_length(clip): return max(k['keytime'] for b in clip['bones'] for k in b['keyframes'])

ham, hn, horder = load(HAM); bun, bn, border = load(BUN)
hG = rest_globals(hn, horder); bG = rest_globals(bn, border)
hby = {short(i): i for i in hn}; bby = {short(i): i for i in bn}
hclips = {a['id']: a for a in ham['animations']}; bclips = {a['id']: a for a in bun['animations']}

# ---- premise checks: in these clips the Shoulder holds one constant local rotation (so a constant correction in its frame is a
# constant correction in the torso frame), and the hamster's Shoulder/UpperArm keys are still the bunny's
shoulder_key = {}
for c in CLIPS:
    ht, bt = tracks(hclips[c]), tracks(bclips[c])
    for side in ('.L', '.R'):
        sh = hby['Shoulder' + side]; bsh = bby['Shoulder' + side]
        keys = ht[sh][1]; bkeys = bt[bsh][1]
        assert len(keys) == len(bkeys) and all(np.linalg.norm(a - b) < 1e-9 for (_, a), (_, b) in zip(keys, bkeys)), f'{c} {sh}: Shoulder keys differ from the bunny'
        q0 = keys[0][1] if keys else qnorm(bn[bsh]['node']['rotation'])
        assert all(np.linalg.norm(q - q0) < 2e-5 for _, q in keys), f'{c} {sh}: Shoulder is animated; the constant correction would not be exact'
        shoulder_key[(c, side)] = q0
        ua = hby['UpperArm' + side]; bua = bby['UpperArm' + side]
        assert [k for k, _ in ht[ua][1]] == [k for k, _ in bt[bua][1]], f'{c} {ua}: key times differ from the bunny'
        for (_, a), (_, b) in zip(ht[ua][1], bt[bua][1]):
            assert np.linalg.norm(a - b) < 1e-9, f'{c} {ua}: rotation keys already differ from the bunny; run this on the re-based model, once'
        # the hamster keeps the bunny's torso chain (same rest globals above the shoulder), the other premise of the re-base
        dq = qmul(qconj(hG[hn[sh]['parent']][1]), bG[bn[bsh]['parent']][1])
        assert angle_deg(dq) < 0.5, f'{sh}: parent frame differs from the bunny by {angle_deg(dq):.2f} deg'

# ---- skinning setup (body vertices the hand ball must clear)
SIZES = {'POSITION': 3, 'NORMAL': 3, 'TEXCOORD0': 2, 'BLENDWEIGHT0': 2, 'BLENDWEIGHT1': 2, 'BLENDWEIGHT2': 2, 'BLENDWEIGHT3': 2}
mesh = ham['meshes'][0]; attrs = mesh['attributes']; stride = sum(SIZES[a] for a in attrs)
off = {}; o = 0
for a in attrs: off[a] = o; o += SIZES[a]
V = np.array(mesh['vertices'], float).reshape(-1, stride)
used = np.zeros(len(V), bool); used[np.array(mesh['parts'][0]['indices'], int)] = True        # the removed stub's vertices are unreferenced
part = find_part(ham['nodes']); bone_ids = [b['node'] for b in part['bones']]
bw = [a for a in attrs if a.startswith('BLENDWEIGHT')]
bidx = np.stack([V[:, off[a]] for a in bw], 1).astype(int); bwts = np.stack([V[:, off[a] + 1] for a in bw], 1)
dom = np.array([short(bone_ids[i]) for i in bidx[np.arange(len(V)), bwts.argmax(1)]])
ARMY = ('Shoulder', 'UpperArm', 'LowerArm', 'Fist', 'Head', 'Ear')
body = used & ~np.array([any(d.startswith(a) for a in ARMY) for d in dom]); body_dom = dom[body]
invbind = {}
for b in part['bones']:
    t = np.array(b['translation'], float); q = qnorm(b['rotation'])
    M = np.eye(4); M[:3, :3] = qmat(q); M[:3, 3] = t; invbind[b['node']] = np.linalg.inv(M)
def skin_body(G):
    out = np.zeros((body.sum(), 3)); P = np.c_[V[body, :3], np.ones(body.sum())]
    for j in range(len(bw)):
        ids = bidx[body, j]; w = bwts[body, j]
        for bi in np.unique(ids):
            sel = ids == bi
            if not sel.any() or bone_ids[bi] not in invbind: continue
            t, q = G[bone_ids[bi]]; M = np.eye(4); M[:3, :3] = qmat(q); M[:3, 3] = t; M = M @ invbind[bone_ids[bi]]
            out[sel] += w[sel, None] * (P[sel] @ M.T)[:, :3]
    return out

# ---- the correction
def correction(clip_id, tilt_deg):
    """Per UpperArm node: (C_S, C_world, bunny rest arm direction). Q_S = the Shoulder's global rotation with the torso chain at
    rest and the clip's (constant) Shoulder key, so C_S is a constant in the torso frame."""
    C = {}
    for side, sx in (('.L', 1.0), ('.R', -1.0)):
        ua, la, sh = bby['UpperArm' + side], bby['LowerArm' + side], bby['Shoulder' + side]
        d_b = bG[la][0] - bG[ua][0]; d_b /= np.linalg.norm(d_b)
        target = np.array([sx * math.sin(math.radians(tilt_deg)), -math.cos(math.radians(tilt_deg)), 0.0])
        Cw = from_to(d_b, target); QS = qmul(bG[bn[sh]['parent']][1], shoulder_key[(clip_id, side)])
        C[hby['UpperArm' + side]] = (qnorm(qmul(qmul(qconj(QS), Cw), QS)), Cw, d_b)
    return C
def twist_q(deg):
    """Rotation about the bone's own +Y axis (post-multiplied: changes where the elbow bends, not where the upper arm points)."""
    return np.array([0.0, math.sin(math.radians(deg) / 2), 0.0, math.cos(math.radians(deg) / 2)])
def retargeted(tilt_deg, twist=None):
    """Deep-copied Run/Walk clips with the UpperArm keys rewritten as C_S * key * twist; returns ({clip: (clip dict, tracks)}, {clip: corrections})."""
    out = {}; Cs = {}; twist = twist or {}
    for c in CLIPS:
        C = correction(c, tilt_deg); Cs[c] = C
        clip = json.loads(json.dumps(hclips[c]))
        for b in clip['bones']:
            if b['boneId'] in C:
                side = '.L' if b['boneId'].endswith('.L') else '.R'; T = twist_q(twist.get((c, side), 0.0))
                for k in b['keyframes']:
                    if 'rotation' in k: k['rotation'] = [round(float(x), 6) for x in qnorm(qmul(qmul(C[b['boneId']][0], qnorm(k['rotation'])), T))]
        out[c] = (clip, tracks(clip))
    return out, Cs
def bend_vectors(tr_by_clip, c, side):
    """Per sample: upper-arm unit direction u and the forearm's bend vector b = f - (f.u)u (world), from the given tracks."""
    tr = tr_by_clip[c]; L = clip_length(hclips[c]); U = []; B = []
    for s in range(SAMPLES):
        G = pose(hn, horder, tr, L * s / SAMPLES)
        ua, la, fi = hby['UpperArm' + side], hby['LowerArm' + side], hby['Fist' + side]
        u = G[la][0] - G[ua][0]; u /= np.linalg.norm(u); f = G[fi][0] - G[la][0]; f /= np.linalg.norm(f)
        U.append(u); B.append(f - np.dot(f, u) * u)
    return np.array(U), np.array(B)
def derive_twist(tilt_deg):
    """Per clip and side: the whole-degree twist about the upper arm's axis that puts the forearm's bend into the body's fore/aft
    plane (least squares over the cycle on the bend's sideways component, bend pointing forward), instead of across the belly."""
    rt, _ = retargeted(tilt_deg); tw = {}
    for c in CLIPS:
        for side in ('.L', '.R'):
            U, B = bend_vectors({k: v[1] for k, v in rt.items()}, c, side)
            best = None
            for phi in range(-180, 180):
                a = math.radians(phi); ca, sa = math.cos(a), math.sin(a)
                Bp = B * ca + np.cross(U, B) * sa + U * (np.sum(U * B, 1, keepdims=True)) * (1 - ca)   # Rodrigues about u
                score = float(np.sum(Bp[:, 0] ** 2)); fwd = float(np.mean(Bp[:, 2]))
                if fwd > 0 and (best is None or score < best[0]): best = (score, phi, fwd)
            tw[(c, side)] = float(best[1])
    return tw
def evaluate(tr_by_clip):
    """Per clip: min ball-to-body clearance, hand-ball x range, upper-arm angle from straight down range, fore/aft swing range."""
    rep = {}
    for c in CLIPS:
        tr = tr_by_clip[c]; L = clip_length(hclips[c]); clear = 1e9; worst = None; ballx = []; down = []; swing = []; lateral = 1e9
        for s in range(SAMPLES):
            t = L * s / SAMPLES; G = pose(hn, horder, tr, t); body_v = skin_body(G)
            for side in ('.L', '.R'):
                ua, la, fi = hby['UpperArm' + side], hby['LowerArm' + side], hby['Fist' + side]
                e, f = G[la][0], G[fi][0]; ball = e + BALL_AT * (f - e)
                dist = np.linalg.norm(body_v - ball, axis=1); k = int(np.argmin(dist)); cl = float(dist[k]) - BALL_R
                if cl < clear: clear = cl; worst = (t, side, ball, body_v[k], body_dom[k])
                # lateral gap: ball edge vs the body's widest point at the ball's height and depth (same side)
                win = (np.abs(body_v[:, 1] - ball[1]) < 0.06) & (np.abs(body_v[:, 2] - ball[2]) < 0.06) & (np.sign(body_v[:, 0]) == np.sign(ball[0]))
                if win.any(): lateral = min(lateral, abs(ball[0]) - BALL_R - float(np.max(np.abs(body_v[win, 0]))))
                ballx.append(abs(ball[0]))
                d = e - G[ua][0]; d /= np.linalg.norm(d)
                down.append(math.degrees(math.acos(max(-1, min(1, -d[1]))))); swing.append(math.degrees(math.atan2(d[2], -d[1])))
        rep[c] = dict(clear=clear, worst=worst, lateral=lateral, ballx=(min(ballx), max(ballx)), down=(min(down), max(down)), swing=(min(swing), max(swing)))
    return rep
def show(tag, rep):
    for c in CLIPS:
        r = rep[c]; t, side, ball, v, bone = r['worst']
        print(f"  {tag:8s} {c:4s}: ball-body clearance {r['clear']:+.3f} (t={t:.0f}ms {side} ball {np.round(ball, 2).tolist()} nearest {np.round(v, 2).tolist()} [{bone}])  "
              f"lateral gap {r['lateral']:+.3f}  |ball x| {r['ballx'][0]:.2f}..{r['ballx'][1]:.2f}  "
              f"upper arm from down {r['down'][0]:.0f}..{r['down'][1]:.0f} deg  fore/aft swing {r['swing'][0]:+.0f}..{r['swing'][1]:+.0f} deg")

print('bunny rest upper-arm direction (world):', {s: np.round(correction('Run', 0)[hby['UpperArm' + s]][2], 3).tolist() for s in ('.L', '.R')})
print('hamster, before (bunny directions):'); show('before', evaluate({c: tracks(hclips[c]) for c in CLIPS}))

def bend_report(tr_by_clip):
    for c in CLIPS:
        for side in ('.L', '.R'):
            U, B = bend_vectors(tr_by_clip, c, side); m = B.mean(0); el = np.degrees(np.arcsin(np.clip(np.linalg.norm(B, axis=1), 0, 1)))
            print(f'    {c:4s} {side}: forearm bend, mean sideways {m[0]:+.2f} (out is {"+" if side == ".L" else "-"}), mean forward {m[2]:+.2f}; elbow bend {el.min():.0f}..{el.max():.0f} deg')

# ---- derive the tilt: smallest whole degree with the ball clear of the body over both cycles (each with its own derived twist)
chosen = None
if FORCED_TILT is None:
    for tilt in range(0, MAX_TILT + 1):
        tw = derive_twist(tilt); rt, _ = retargeted(tilt, tw); rep = evaluate({c: rt[c][1] for c in CLIPS}); worst = min(rep[c]['clear'] for c in CLIPS)
        print(f'  tilt {tilt:2d} deg (twist {", ".join(f"{k[0]}{k[1]} {v:+.0f}" for k, v in tw.items())}): worst ball-body clearance {worst:+.3f}')
        if worst >= CLEAR: chosen = tilt; break
    assert chosen is not None, f'no tilt up to {MAX_TILT} deg keeps the hand ball clear of the body'
else:
    chosen = FORCED_TILT
tw = derive_twist(chosen); rt, Cs = retargeted(chosen, tw)
print(f'tilt = {chosen} deg outward from straight down (the smallest whole degree at which the hand ball never presses more than {SINK:.0%} of its radius into the body)')
for c in CLIPS:
    for side in ('.L', '.R'):
        cs, cw, d_b = Cs[c][hby['UpperArm' + side]]
        print(f'  {c:4s} UpperArm{side}: C_world {np.round(cw, 4).tolist()} ({angle_deg(cw):.1f} deg), C_S {np.round(cs, 4).tolist()}, twist {tw[(c, side)]:+.0f} deg')
print('  forearm bend before (bunny):'); bend_report({c: tracks(hclips[c]) for c in CLIPS})
print('  forearm bend after:'); bend_report({c: rt[c][1] for c in CLIPS})
print('hamster, after:'); rep = evaluate({c: rt[c][1] for c in CLIPS}); show('after', rep)

# ---- the retargeted upper-arm direction must equal C_world * the original direction up to the torso's own motion in the clip
for c in CLIPS:
    tr = rt[c][1]; orig = tracks(hclips[c]); L = clip_length(hclips[c]); worst = 0.0
    for s in range(SAMPLES):
        t = L * s / SAMPLES; G = pose(hn, horder, tr, t); G0 = pose(hn, horder, orig, t)
        for side in ('.L', '.R'):
            ua, la = hby['UpperArm' + side], hby['LowerArm' + side]; cw = Cs[c][ua][1]
            d0 = G0[la][0] - G0[ua][0]; d0 /= np.linalg.norm(d0); d1 = G[la][0] - G[ua][0]; d1 /= np.linalg.norm(d1)
            worst = max(worst, math.degrees(math.acos(max(-1, min(1, float(np.dot(qrot(cw, d0), d1)))))))
    print(f'  {c}: retargeted upper-arm direction vs C_world * original, max difference {worst:.2f} deg (the torso\'s own motion in the clip)')

# ---- write: only the four UpperArm tracks in Run and Walk may differ
if DRY: print('dry run: nothing written'); sys.exit(0)
before = json.dumps(ham, sort_keys=True)
for c in CLIPS:
    for i, a in enumerate(ham['animations']):
        if a['id'] == c: ham['animations'][i] = rt[c][0]
after_d = json.loads(json.dumps(ham)); orig_d = json.loads(before)
changed = 0
for a0, a1 in zip(orig_d['animations'], after_d['animations']):
    assert a0['id'] == a1['id']
    for b0, b1 in zip(a0['bones'], a1['bones']):
        assert b0['boneId'] == b1['boneId']
        if b0 != b1:
            assert a0['id'] in CLIPS and 'UpperArm' in b0['boneId'], f"unexpected change in {a0['id']} {b0['boneId']}"
            changed += 1
for k in ('meshes', 'materials', 'nodes'):
    assert json.dumps(orig_d[k], sort_keys=True) == json.dumps(after_d[k], sort_keys=True), f'{k} changed'
assert changed == 4, changed
print(f'tracks rewritten: {changed} (UpperArm.L/R in Run and Walk); writing {HAM}')
json.dump(ham, open(HAM, 'w'), separators=(',', ':'))
