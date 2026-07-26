package com.mojang.rubydung.level;

import com.mojang.rubydung.render.vk.GameRenderer;

/**
 * Immediate-mode geometry builder. Produces interleaved vertices
 * (pos3 + uv2 + color4 + light2 = 11 floats) and flushes them through the Vulkan
 * GameRenderer's streaming buffer.
 *
 * API mirrors the old GL Tesselator: init / vertex / tex / color / light / flush.
 * Color is always written; defaults to the renderer's current color (or white) when not set.
 * Light is a pair (sky, block): the shader dims sky light with the day/night multiplier but
 * never dims block light, so a torch still lights a cave at midnight. Geometry that is not
 * part of the world (UI, particles, entities) leaves the default (1, 0) and behaves exactly
 * as it did when the vertex carried no light at all.
 */
public class Tesselator {
    private static final int MAX_VERTICES = 524_288; // 2^19 hard cap
    /** pos3 + uv2 + color4 + light2 — must match Pipelines.VERTEX_STRIDE. */
    public static final int FLOATS_PER_VERTEX = 11;

    private float u, v, r, g, b, a = 1f;
    private float lightSky = 1f, lightBlock = 0f;
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
        lightSky = 1f;
        lightBlock = 0f;
    }

    /**
     * Light for the following vertices: {@code sky} is scaled by the day/night multiplier in
     * the shader, {@code block} is not. Defaults to (1, 0) — i.e. "fully lit by daylight".
     */
    public void light(float sky, float block) {
        lightSky = sky;
        lightBlock = block;
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
        data[i + 9]  = lightSky;
        data[i + 10] = lightBlock;
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
