#!/usr/bin/env python3
"""Strip the distal-forearm hand/glove geometry from a character g3dj so a drawn ball can stand in for the hand
(HeroRig renders a coloured ball at each hand). The Quaternius astronauts' "gloves" are splayed, non-articulating
finger geometry skinned to the LowerArm bone (the finger bones are vestigial); the hamster's paws are the same.
We remove the triangles whose dominant-weight vertex is a LowerArm vertex beyond a wrist threshold (projected along
the elbow->hand axis). The bones are left untouched, so grip reach and the ball's anchor are unchanged.

Run from the repo root AFTER restoring the pristine models (git checkout), e.g.:
    python3 tools/models/strip_hand_geometry.py
Thresholds were chosen from each model's elbow->hand vertex histogram (the gap at the wrist).
"""
import json, numpy as np
SIZES={'POSITION':3,'NORMAL':3,'COLOR':4,'TEXCOORD0':2,'TANGENT':3,'BINORMAL':3}
def strip(path, armpairs, thr):
    m=json.load(open(path)); bones=None
    def find(ns):
        nonlocal bones
        for x in ns:
            for pp in x.get('parts',[]):
                if 'bones' in pp: bones=pp['bones']
            if 'children' in x: find(x['children'])
    find(m['nodes']); bidx={b['node']:i for i,b in enumerate(bones)}
    mesh=m['meshes'][0]; attrs=mesh['attributes']; vs=0; off={}; o=0
    for a in attrs:
        sz=2 if a.startswith('BLENDWEIGHT') else SIZES[a]; off[a]=o; o+=sz; vs+=sz
    V=mesh['vertices']; nv=len(V)//vs; bw=[a for a in attrs if a.startswith('BLENDWEIGHT')]
    dom=lambda vi: max(((V[vi*vs+off[a]+1], int(round(V[vi*vs+off[a]]))) for a in bw))[1]
    pos=lambda vi: np.array(V[vi*vs+off['POSITION']:vi*vs+off['POSITION']+3])
    domc=[dom(vi) for vi in range(nv)]; mark=[False]*nv
    for la,ua in armpairs:
        li,ui=bidx[la],bidx[ua]
        lav=[vi for vi in range(nv) if domc[vi]==li]; elbow=np.mean([pos(vi) for vi in range(nv) if domc[vi]==ui],0)
        P=np.array([pos(vi) for vi in lav]); axis=P.mean(0)-elbow; axis/=np.linalg.norm(axis)+1e-9
        for vi in lav:
            if (pos(vi)-elbow)@axis > thr: mark[vi]=True
    for p in mesh['parts']:
        idx=p['indices']; kept=[i for t in range(0,len(idx),3) for i in idx[t:t+3] if not (mark[idx[t]] or mark[idx[t+1]] or mark[idx[t+2]])]
        print(f"{path}: dropped {(len(idx)-len(kept))//3}/{len(idx)//3} tris"); p['indices']=kept
    json.dump(m, open(path,'w'))
def strip_cuff(path, armpairs, start=0.55, maxlum=0.45):
    """The astronauts' metal wrist cuff: the dark grey rings (vertex colours 0.35 and 0.2) at the end of the LowerArm,
    past the light sleeve (0.63). Drops every triangle with a LowerArm-dominant vertex that is dark and beyond
    `start` along the elbow->hand axis. Safe to run again (nothing left to drop)."""
    m=json.load(open(path)); bones=None
    def find(ns):
        nonlocal bones
        for x in ns:
            for pp in x.get('parts',[]):
                if 'bones' in pp: bones=pp['bones']
            if 'children' in x: find(x['children'])
    find(m['nodes']); bidx={b['node']:i for i,b in enumerate(bones)}
    mesh=m['meshes'][0]; attrs=mesh['attributes']; vs=0; off={}; o=0
    for a in attrs:
        sz=2 if a.startswith('BLENDWEIGHT') else SIZES[a]; off[a]=o; o+=sz; vs+=sz
    V=mesh['vertices']; nv=len(V)//vs; bw=[a for a in attrs if a.startswith('BLENDWEIGHT')]
    dom=lambda vi: max(((V[vi*vs+off[a]+1], int(round(V[vi*vs+off[a]]))) for a in bw))[1]
    pos=lambda vi: np.array(V[vi*vs+off['POSITION']:vi*vs+off['POSITION']+3])
    lum=lambda vi: max(V[vi*vs+off['COLOR']:vi*vs+off['COLOR']+3])
    domc=[dom(vi) for vi in range(nv)]; mark=[False]*nv
    for la,ua in armpairs:
        li,ui=bidx[la],bidx[ua]
        lav=[vi for vi in range(nv) if domc[vi]==li]; elbow=np.mean([pos(vi) for vi in range(nv) if domc[vi]==ui],0)
        axis=np.mean([pos(vi) for vi in lav],0)-elbow; axis/=np.linalg.norm(axis)+1e-9
        for vi in lav:
            if (pos(vi)-elbow)@axis > start and lum(vi) < maxlum: mark[vi]=True
    for p in mesh['parts']:
        idx=p['indices']; kept=[i for t in range(0,len(idx),3) for i in idx[t:t+3] if not (mark[idx[t]] or mark[idx[t+1]] or mark[idx[t+2]])]
        print(f"{path}: cuff dropped {(len(idx)-len(kept))//3}/{len(idx)//3} tris"); p['indices']=kept
    json.dump(m, open(path,'w'))

if __name__=='__main__':
    AR=[('n14_LowerArm.L','n15_UpperArm.L'),('n29_LowerArm.R','n30_UpperArm.R')]
    for f in ['finnthefrog','raetheredpanda','fernandotheflamingo','barbarathebee']:
        strip(f'assets/space/astronaut_{f}.g3dj', AR, 0.90)
        strip_cuff(f'assets/space/astronaut_{f}.g3dj', AR)
    strip('assets/hero/hamster.g3dj', [('n10_LowerArm.L','n11_UpperArm.L'),('n14_LowerArm.R','n15_UpperArm.R')], 0.19)
