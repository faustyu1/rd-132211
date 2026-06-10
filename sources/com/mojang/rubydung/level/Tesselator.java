package com.mojang.rubydung.level;

import com.mojang.rubydung.render.vk.GameRenderer;

/**
 * Immediate-mode geometry builder. Produces interleaved vertices (pos3 + uv2 + color4 = 9 floats)
 * and flushes them through the Vulkan GameRenderer's streaming buffer.
 *
 * API mirrors the old GL Tesselator: init / vertex / tex / color / flush.
 * Color is always written; defaults to the renderer's current color (or white) when not set.
 */
public class Tesselator {
    private static final int MAX_VERTICES = 524_288; // 2^19 hard cap
    private static final int FLOATS_PER_VERTEX = 9;

    private float u, v, r, g, b, a = 1f;
    // grows on demand; starts small so the many long-lived Tesselators stay cheap
    private float[] data = new float[4096 * FLOATS_PER_VERTEX];
    private int vertices = 0;
    private boolean hasColor = false;
    private boolean hasTexture = false;

    /** Flush accumulated quads (4 verts per quad) as triangles. */
    public void flush() {
        if (vertices > 0) {
            GameRenderer r = GameRenderer.instance;
            r.drawStreamQuads(data, vertices * FLOATS_PER_VERTEX, vertices);
        }
        clear();
    }

    /** Flush accumulated vertices as a LINE_LIST (caller must emit vertices in pairs). */
    public void flushLines() {
        if (vertices > 0) {
            GameRenderer r = GameRenderer.instance;
            r.drawStreamLines(data, vertices * FLOATS_PER_VERTEX, vertices);
        }
        clear();
    }

    private void clear() {
        vertices = 0;
    }

    public void init() {
        clear();
        hasColor = false;
        hasTexture = false;
    }

    public void tex(float u, float v) {
        hasTexture = true;
        this.u = u;
        this.v = v;
    }

    public void color(float r, float g, float b) { color(r, g, b, 1f); }
    public void color(float r, float g, float b, float a) {
        hasColor = true;
        this.r = r; this.g = g; this.b = b; this.a = a;
    }

    public void vertex(float x, float y, float z) {
        if (vertices == MAX_VERTICES)
            throw new IllegalStateException("Tesselator vertex buffer overflow (" + MAX_VERTICES + " vertices)");
        int i = vertices * FLOATS_PER_VERTEX;
        if (i + FLOATS_PER_VERTEX > data.length) {
            int newLen = Math.min(data.length * 2, MAX_VERTICES * FLOATS_PER_VERTEX);
            data = java.util.Arrays.copyOf(data, newLen);
        }
        data[i]     = x;
        data[i + 1] = y;
        data[i + 2] = z;
        if (hasTexture) {
            data[i + 3] = u;
            data[i + 4] = v;
        } else {
            data[i + 3] = 0f;
            data[i + 4] = 0f;
        }
        if (hasColor) {
            data[i + 5] = r;
            data[i + 6] = g;
            data[i + 7] = b;
            data[i + 8] = a;
        } else {
            GameRenderer gr = GameRenderer.instance;
            data[i + 5] = gr.colR;
            data[i + 6] = gr.colG;
            data[i + 7] = gr.colB;
            data[i + 8] = gr.colA;
        }
        vertices++;
    }

    public int getVertexCount() { return vertices; }

    /**
     * Returns the raw backing array (may be larger than the live data). Valid range is
     * the first {@code getVertexCount() * 9} floats. Only safe when this Tesselator is
     * not reused afterwards (e.g. a throwaway chunk-build instance).
     */
    public float[] getBackingArray() { return data; }
}
