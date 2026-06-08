package com.mojang.rubydung.level;

import com.mojang.rubydung.phys.AABB;
import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.opengl.GL11;

public class Frustum {
    private static final Frustum frustum = new Frustum();

    public float[][] m_Frustum = new float[6][4];
    private final FloatBuffer _proj = MemoryUtil.memAllocFloat(16);
    private final FloatBuffer _modl = MemoryUtil.memAllocFloat(16);
    private final float[] proj = new float[16];
    private final float[] modl = new float[16];
    private final float[] clip = new float[16];

    private Frustum() {}

    public static Frustum getFrustum() {
        frustum.calculateFrustum();
        return frustum;
    }

    private void normalizePlane(float[][] f, int side) {
        float mag = (float) Math.sqrt(
            f[side][0] * f[side][0] + f[side][1] * f[side][1] + f[side][2] * f[side][2]);
        f[side][0] /= mag;
        f[side][1] /= mag;
        f[side][2] /= mag;
        f[side][3] /= mag;
    }

    private void calculateFrustum() {
        _proj.clear();
        _modl.clear();
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, _proj);
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, _modl);
        _proj.flip().limit(16); _proj.get(proj);
        _modl.flip().limit(16); _modl.get(modl);

        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += modl[r * 4 + k] * proj[k * 4 + c];
                clip[r * 4 + c] = sum;
            }
        }

        m_Frustum[0][0] = clip[3]  - clip[0];  m_Frustum[0][1] = clip[7]  - clip[4];
        m_Frustum[0][2] = clip[11] - clip[8];  m_Frustum[0][3] = clip[15] - clip[12];
        normalizePlane(m_Frustum, 0);

        m_Frustum[1][0] = clip[3]  + clip[0];  m_Frustum[1][1] = clip[7]  + clip[4];
        m_Frustum[1][2] = clip[11] + clip[8];  m_Frustum[1][3] = clip[15] + clip[12];
        normalizePlane(m_Frustum, 1);

        m_Frustum[2][0] = clip[3]  + clip[1];  m_Frustum[2][1] = clip[7]  + clip[5];
        m_Frustum[2][2] = clip[11] + clip[9];  m_Frustum[2][3] = clip[15] + clip[13];
        normalizePlane(m_Frustum, 2);

        m_Frustum[3][0] = clip[3]  - clip[1];  m_Frustum[3][1] = clip[7]  - clip[5];
        m_Frustum[3][2] = clip[11] - clip[9];  m_Frustum[3][3] = clip[15] - clip[13];
        normalizePlane(m_Frustum, 3);

        m_Frustum[4][0] = clip[3]  - clip[2];  m_Frustum[4][1] = clip[7]  - clip[6];
        m_Frustum[4][2] = clip[11] - clip[10]; m_Frustum[4][3] = clip[15] - clip[14];
        normalizePlane(m_Frustum, 4);

        m_Frustum[5][0] = clip[3]  + clip[2];  m_Frustum[5][1] = clip[7]  + clip[6];
        m_Frustum[5][2] = clip[11] + clip[10]; m_Frustum[5][3] = clip[15] + clip[14];
        normalizePlane(m_Frustum, 5);
    }

    public boolean cubeInFrustum(AABB aabb) {
        return cubeInFrustum(aabb.x0, aabb.y0, aabb.z0, aabb.x1, aabb.y1, aabb.z1);
    }

    public boolean cubeInFrustum(float x1, float y1, float z1, float x2, float y2, float z2) {
        for (var plane : m_Frustum) {
            if (plane[0]*x1 + plane[1]*y1 + plane[2]*z1 + plane[3] <= 0 &&
                plane[0]*x2 + plane[1]*y1 + plane[2]*z1 + plane[3] <= 0 &&
                plane[0]*x1 + plane[1]*y2 + plane[2]*z1 + plane[3] <= 0 &&
                plane[0]*x2 + plane[1]*y2 + plane[2]*z1 + plane[3] <= 0 &&
                plane[0]*x1 + plane[1]*y1 + plane[2]*z2 + plane[3] <= 0 &&
                plane[0]*x2 + plane[1]*y1 + plane[2]*z2 + plane[3] <= 0 &&
                plane[0]*x1 + plane[1]*y2 + plane[2]*z2 + plane[3] <= 0 &&
                plane[0]*x2 + plane[1]*y2 + plane[2]*z2 + plane[3] <= 0) {
                return false;
            }
        }
        return true;
    }
}
