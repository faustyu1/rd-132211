package com.mojang.rubydung.level;

/**
 * Geometry builder for chunk section meshes, producing the packed 16-byte vertex the world
 * pipelines read. The old float layout cost 44 bytes and spent most of them on precision
 * nothing in a voxel world can use: block coordinates land on a 1/16 grid, a texture tile is
 * 16 pixels across, and light is a value from 0 to 15.
 *
 * <pre>
 *   bytes 0..5   position, section-local, in 1/{@value #POS_SCALE} of a block (3 x uint16)
 *   bytes 6..7   texture array layer (uint16, high half of the same attribute)
 *   bytes 8..11  colour RGBA (4 x unorm8)
 *   bytes 12..15 u, v within the tile, then sky light and block light (4 x unorm8)
 * </pre>
 *
 * Positions are relative to the section origin, which the vertex shader adds back from a
 * per-instance attribute. That is what lets every section share one vertex buffer and one
 * draw call: nothing in the vertex data depends on where in the world the section sits.
 */
public final class ChunkTesselator {
    /** Position fixed-point scale. 16 blocks x 2048 = 32768, comfortably inside uint16. */
    public static final int POS_SCALE = 2048;
    /** Bytes per vertex, matching the pipeline's vertex input stride. */
    public static final int VERTEX_BYTES = 16;
    private static final int INTS_PER_VERTEX = VERTEX_BYTES / 4;
    private static final int MAX_VERTICES = 1 << 19;

    private int[] data = new int[4096 * INTS_PER_VERTEX];
    private int vertices;

    // section origin in world space; vertex() stores positions relative to it
    private float ox, oy, oz;

    // current vertex attributes
    private int layer;
    private int u, v;
    private int r = 255, g = 255, b = 255, a = 255;
    private int sky = 255, block;

    public void init(float originX, float originY, float originZ) {
        vertices = 0;
        ox = originX; oy = originY; oz = originZ;
        layer = 0;
        u = 0; v = 0;
        r = g = b = a = 255;
        sky = 255; block = 0;
    }

    /** Texture array layer plus the corner's position inside that tile, both 0..1. */
    public void tex(int texLayer, float tu, float tv) {
        this.layer = texLayer;
        this.u = unorm(tu);
        this.v = unorm(tv);
    }

    public void color(float cr, float cg, float cb, float ca) {
        r = unorm(cr); g = unorm(cg); b = unorm(cb); a = unorm(ca);
    }

    /** Sky and block light, 0..1. The shader dims only the sky half with the day/night cycle. */
    public void light(float skyLight, float blockLight) {
        sky = unorm(skyLight);
        block = unorm(blockLight);
    }

    public void vertex(float x, float y, float z) {
        if (vertices == MAX_VERTICES)
            throw new IllegalStateException("chunk mesh overflow (" + MAX_VERTICES + " vertices)");
        int i = vertices * INTS_PER_VERTEX;
        if (i + INTS_PER_VERTEX > data.length)
            data = java.util.Arrays.copyOf(data, Math.min(data.length * 2, MAX_VERTICES * INTS_PER_VERTEX));

        int px = pos(x - ox), py = pos(y - oy), pz = pos(z - oz);
        data[i]     = px | (py << 16);
        data[i + 1] = pz | (layer << 16);
        data[i + 2] = r | (g << 8) | (b << 16) | (a << 24);
        data[i + 3] = u | (v << 8) | (sky << 16) | (block << 24);
        vertices++;
    }

    private static int pos(float local) {
        int q = Math.round(local * POS_SCALE);
        return Math.clamp(q, 0, 0xFFFF);
    }

    private static int unorm(float f) {
        return Math.clamp(Math.round(f * 255f), 0, 255);
    }

    public int getVertexCount() { return vertices; }
    public int getIntCount() { return vertices * INTS_PER_VERTEX; }

    /**
     * The raw backing array, which is larger than the live data and is reused by the next
     * section built on this thread. Only the first {@link #getIntCount()} ints are valid, and
     * the caller must copy what it wants to keep.
     */
    public int[] getBackingArray() { return data; }
}
