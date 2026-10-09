import bpy, sys, json, os, numpy as np
src=sys.argv[-2]; out=sys.argv[-1]
bpy.ops.wm.open_mainfile(filepath=src)
o=bpy.data.objects['Hamster']
dg=bpy.context.evaluated_depsgraph_get()
oe=o.evaluated_get(dg); me=oe.to_mesh()
M=o.matrix_world
me.transform(M)
me.calc_loop_triangles()
uv=me.uv_layers.active.data
nrm=me.corner_normals
# Blender (x right, y depth, z up) -> libGDX (x right, y up, z toward viewer): (x, z, -y)
def conv(v): return (v[0], v[2], -v[1])
verts=[]; idx={}; tris=[]
for tri in me.loop_triangles:
    for li in tri.loops:
        vi=me.loops[li].vertex_index
        p=me.vertices[vi].co; n=nrm[li].vector; u=uv[li].uv
        key=(vi, round(n[0],3),round(n[1],3),round(n[2],3), round(u[0],4), round(u[1],4))
        if key not in idx:
            idx[key]=len(verts); verts.append((conv(p), conv(n), (u[0],1.0-u[1])))
        tris.append(idx[key])
P=np.array([v[0] for v in verts]); mn=P.min(0); mx=P.max(0)
print('bbox',mn,mx,'verts',len(verts),'tris',len(tris)//3)
# centre x,z; feet at y=0
ctr=np.array([(mn[0]+mx[0])/2, mn[1], (mn[2]+mx[2])/2])
flat=[]
for p,n,u in verts: flat += [round(p[0]-ctr[0],4),round(p[1]-ctr[1],4),round(p[2]-ctr[2],4),round(n[0],3),round(n[1],3),round(n[2],3),round(u[0],4),round(u[1],4)]
img=bpy.data.images['HamsterBaseColor']
png=os.path.join(out,'hamster_full.png'); img.filepath_raw=png; img.file_format='PNG'; img.save()
g3={'version':[0,1],'id':'hamster','meshes':[{'attributes':['POSITION','NORMAL','TEXCOORD0'],'vertices':flat,'parts':[{'id':'part1','type':'TRIANGLES','indices':tris}]}],
 'materials':[{'id':'mat1','diffuse':[1,1,1],'textures':[{'id':'tex1','filename':'hamster.png','type':'DIFFUSE'}]}],
 'nodes':[{'id':'hamster','parts':[{'meshpartid':'part1','materialid':'mat1','uvMapping':[[0]]}]}],'animations':[]}
json.dump(g3,open(os.path.join(out,'hamster.g3dj'),'w'),separators=(',',':'))
# front direction hint: where is the nose? print the vertex with max -y(blender) extent etc.
