package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;

/**
 * Turns a flat sprite into a voxel-style 3D mesh: the opaque silhouette is quantised into cells, each cell gets a
 * column whose depth grows with distance from the silhouette edge (a pillow/bevel), front and back faces keep the
 * sprite colours, and side walls are generated wherever neighbouring columns differ.
 */
public final class VoxelPart {
    private VoxelPart() {}

    /** Local origin = sprite centre; +Z is the front. unit = world units per source pixel. */
    public static Model build(Pixmap pm, Texture tex, float unit, int cell, int maxLevels, float levelDepth) {
        final int W = pm.getWidth(), H = pm.getHeight();
        final int gw = (W + cell - 1) / cell, gh = (H + cell - 1) / cell;
        boolean[][] solid = new boolean[gw][gh];
        for (int gx = 0; gx < gw; gx++) for (int gy = 0; gy < gh; gy++) {
            int hits = 0;
            for (int k = 0; k < 4; k++) {
                int px = Math.min(W - 1, gx * cell + (k % 2 == 0 ? cell / 3 : 2 * cell / 3));
                int py = Math.min(H - 1, gy * cell + (k < 2 ? cell / 3 : 2 * cell / 3));
                if ((pm.getPixel(px, py) & 0xff) > 128) hits++;
            }
            solid[gx][gy] = hits >= 2;
        }
        // distance (in cells) to the nearest empty cell, 4-neighbour BFS-ish chamfer
        int[][] dist = new int[gw][gh];
        for (int gx = 0; gx < gw; gx++) for (int gy = 0; gy < gh; gy++) dist[gx][gy] = solid[gx][gy] ? 999 : 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int gx = 0; gx < gw; gx++) for (int gy = 0; gy < gh; gy++) if (solid[gx][gy]) {
                int d = dist[gx][gy];
                d = Math.min(d, (gx > 0 ? dist[gx - 1][gy] : 0) + 1);
                d = Math.min(d, (gy > 0 ? dist[gx][gy - 1] : 0) + 1);
                dist[gx][gy] = d;
            }
            for (int gx = gw - 1; gx >= 0; gx--) for (int gy = gh - 1; gy >= 0; gy--) if (solid[gx][gy]) {
                int d = dist[gx][gy];
                d = Math.min(d, (gx < gw - 1 ? dist[gx + 1][gy] : 0) + 1);
                d = Math.min(d, (gy < gh - 1 ? dist[gx][gy + 1] : 0) + 1);
                dist[gx][gy] = d;
            }
        }
        int[][] lvl = new int[gw][gh];
        for (int gx = 0; gx < gw; gx++) for (int gy = 0; gy < gh; gy++)
            if (solid[gx][gy]) lvl[gx][gy] = Math.min(maxLevels, 1 + (int) Math.floor(Math.sqrt(Math.max(0, dist[gx][gy] - 1)) * 1.6));

        Material mat = new Material(ColorAttribute.createDiffuse(1f, 1f, 1f, 1f), ColorAttribute.createEmissive(0.22f, 0.22f, 0.24f, 1f),
                TextureAttribute.createDiffuse(tex));
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder b = mb.part("voxel", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal | Usage.TextureCoordinates, mat);
        final float cw = cell * unit;
        for (int gx = 0; gx < gw; gx++) for (int gy = 0; gy < gh; gy++) {
            if (!solid[gx][gy]) continue;
            float x0 = (gx * cell - W / 2f) * unit, x1 = x0 + cw;
            float y1 = (H / 2f - gy * cell) * unit, y0 = y1 - cw;
            float h = lvl[gx][gy] * levelDepth;
            float u = Math.min(W - 1, gx * cell + cell / 2f) / W, v = Math.min(H - 1, gy * cell + cell / 2f) / H;
            b.setUVRange(u, v, u, v);
            quad(b, x0, y0, h, x1, y0, h, x1, y1, h, x0, y1, h, 0, 0, 1);
            quad(b, x1, y0, -h, x0, y0, -h, x0, y1, -h, x1, y1, -h, 0, 0, -1);
            // walls: +X, -X, +Y, -Y
            int nR = gx < gw - 1 && solid[gx + 1][gy] ? lvl[gx + 1][gy] : 0;
            int nL = gx > 0 && solid[gx - 1][gy] ? lvl[gx - 1][gy] : 0;
            int nU = gy > 0 && solid[gx][gy - 1] ? lvl[gx][gy - 1] : 0;
            int nD = gy < gh - 1 && solid[gx][gy + 1] ? lvl[gx][gy + 1] : 0;
            int me = lvl[gx][gy];
            if (nR < me) { float hn = nR * levelDepth; wallX(b, x1, y0, y1, hn, h, 1); }
            if (nL < me) { float hn = nL * levelDepth; wallX(b, x0, y0, y1, hn, h, -1); }
            if (nU < me) { float hn = nU * levelDepth; wallY(b, y1, x0, x1, hn, h, 1); }
            if (nD < me) { float hn = nD * levelDepth; wallY(b, y0, x0, x1, hn, h, -1); }
        }
        return mb.end();
    }

    private static void wallX(MeshPartBuilder b, float x, float y0, float y1, float hn, float h, int nx) {
        quad(b, x, y0, h, x, y0, hn, x, y1, hn, x, y1, h, nx, 0, 0);
        quad(b, x, y0, -hn, x, y0, -h, x, y1, -h, x, y1, -hn, nx, 0, 0);
    }

    private static void wallY(MeshPartBuilder b, float y, float x0, float x1, float hn, float h, int ny) {
        quad(b, x0, y, h, x1, y, h, x1, y, hn, x0, y, hn, 0, ny, 0);
        quad(b, x0, y, -hn, x1, y, -hn, x1, y, -h, x0, y, -h, 0, ny, 0);
    }

    private static final Vector3 a = new Vector3(), c = new Vector3(), n = new Vector3();

    /** Emits a quad, flipping the winding if needed so it faces along the given normal. */
    private static void quad(MeshPartBuilder b, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, float nx, float ny, float nz) {
        a.set(x1 - x0, y1 - y0, z1 - z0); c.set(x2 - x0, y2 - y0, z2 - z0);
        n.set(a).crs(c);
        if (n.dot(nx, ny, nz) >= 0) b.rect(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, nx, ny, nz);
        else b.rect(x3, y3, z3, x2, y2, z2, x1, y1, z1, x0, y0, z0, nx, ny, nz);
    }
}
