package com.mojang.rubydung.level;

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.opengl.GL11;

public class Tesselator {
    private static final int MAX_VERTICES = 524_288; // 2^19

    private float u, v, r, g, b, a = 1f;
    private final FloatBuffer vertexBuffer   = MemoryUtil.memAllocFloat(MAX_VERTICES * 3);
    private final FloatBuffer texCoordBuffer = MemoryUtil.memAllocFloat(MAX_VERTICES * 2);
    private final FloatBuffer colorBuffer    = MemoryUtil.memAllocFloat(MAX_VERTICES * 4);
    private int vertices = 0;
    private boolean hasColor = false;
    private boolean hasTexture = false;

    public void flush() {
        vertexBuffer.flip();
        texCoordBuffer.flip();
        colorBuffer.flip();

        GL11.glVertexPointer(3, GL11.GL_FLOAT, 0, vertexBuffer);
        if (hasTexture) GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 0, texCoordBuffer);
        if (hasColor)   GL11.glColorPointer(4, GL11.GL_FLOAT, 0, colorBuffer);

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        if (hasTexture) GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        if (hasColor)   GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

        GL11.glDrawArrays(GL11.GL_QUADS, 0, vertices);

        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        if (hasTexture) GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        if (hasColor)   GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);

        clear();
    }

    private void clear() {
        vertices = 0;
        vertexBuffer.clear();
        texCoordBuffer.clear();
        colorBuffer.clear();
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
        int vi = vertices * 3;
        vertexBuffer.put(vi, x).put(vi + 1, y).put(vi + 2, z);
        if (hasTexture) {
            int ti = vertices * 2;
            texCoordBuffer.put(ti, u).put(ti + 1, v);
        }
        if (hasColor) {
            int ci = vertices * 4;
            colorBuffer.put(ci, r).put(ci + 1, g).put(ci + 2, b).put(ci + 3, a);
        }
        vertices++;
        if (vertices == MAX_VERTICES)
            throw new IllegalStateException("Tesselator vertex buffer overflow (" + MAX_VERTICES + " vertices)");
    }

    public int getVertexCount() { return vertices; }

    public float[] getVertexArray() {
        float[] arr = new float[vertices * 3];
        for (int i = 0; i < arr.length; i++) arr[i] = vertexBuffer.get(i);
        return arr;
    }

    public float[] getTexArray() {
        float[] arr = new float[vertices * 2];
        for (int i = 0; i < arr.length; i++) arr[i] = texCoordBuffer.get(i);
        return arr;
    }

    public float[] getColorArray() {
        float[] arr = new float[vertices * 4];
        for (int i = 0; i < arr.length; i++) arr[i] = colorBuffer.get(i);
        return arr;
    }
}
