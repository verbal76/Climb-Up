"""Re-bind the hamster's whiskers from the arm bones to the head, in place, in assets/hero/hamster.g3dj.

Why: the supplied hamster mesh (rigged by build_rigged_hamster.py from the owner's .blend) has its whiskers - a batch of
small sliver triangles out at the sides of the muzzle - skinned to the LowerArm/UpperArm bones, not the head. The position-
based skin weights in build_rigged_hamster.py give full arm weight to anything wide (|x|>0.62) above chest height, and the
whiskers (wide, at nose height) fall in that band. So the whiskers track the arms and swing into the face as the arms
animate. The owner's editable .blend is not in the repo, so we fix the shipped g3dj directly (same approach as
strip_hand_geometry.py).

What: every vertex that is (a) dominantly weighted to an arm bone and (b) beyond the arm/paw's own reach (|x| > THRESH, where
the forearm rests at |x| < ~1.0 after the hand strip) is re-weighted 100% to the head bone. The paw/forearm geometry (|x| <
THRESH) is left on the arm bones, so the arms still reach and grip normally. Verified in RigShot: whiskers stay on the muzzle
in idle and rope poses, the arms still reach the rope (no detached forearm).

usage:  python3 reskin_whiskers.py <hamster.g3dj>   (edits the file in place)
"""
import json, sys, numpy as np

THRESH = 1.0                       # |x| beyond which arm-skinned geometry is whiskers, not the arm/paw (model units)
HEAD_BONE = 'n7_Head'

path = sys.argv[1] if len(sys.argv) > 1 else 'assets/hero/hamster.g3dj'
d = json.load(open(path)); m = d['meshes'][0]
attrs = m['attributes']; sizes = {'POSITION':3,'NORMAL':3,'TEXCOORD0':2,'BLENDWEIGHT0':2,'BLENDWEIGHT1':2,'BLENDWEIGHT2':2,'BLENDWEIGHT3':2}
stride = sum(sizes[a] for a in attrs)
V = np.array(m['vertices']).reshape(-1, stride)
bones = [b['node'] for b in d['nodes'][0]['children'][0]['parts'][0]['bones']]
arm = {i for i, b in enumerate(bones) if 'Arm' in b}
head = bones.index(HEAD_BONE)

def dominant(v):
    best = (-1, -1.0)
    for o in (8, 10, 12, 14):          # the four (joint, weight) pairs
        j = int(round(v[o])); w = v[o + 1]
        if w > best[1]: best = (j, w)
    return best[0]

n = 0
for v in V:
    if dominant(v) in arm and abs(v[0]) > THRESH:
        v[8], v[9] = float(head), 1.0              # whole weight to the head
        v[10] = v[12] = v[14] = 0.0; v[11] = v[13] = v[15] = 0.0
        n += 1
m['vertices'] = [round(float(x), 6) for x in V.reshape(-1)]
json.dump(d, open(path, 'w'), separators=(',', ':'))
print('re-skinned %d whisker verts (|x| > %.2f, arm -> %s) in %s' % (n, THRESH, HEAD_BONE, path))
