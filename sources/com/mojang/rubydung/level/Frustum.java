package com.mojang.rubydung.level;

import com.mojang.rubydung.phys.AABB;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * View-frustum culling backed by JOML. Updated each frame from the renderer's
 * projection and modelView matrices (instead of reading GL matrix state).
 */
public class Frustum {
    private static final Frustum frustum = new Frustum();

    private final FrustumIntersection intersection = new FrustumIntersection();
    private final Matrix4f clip = new Matrix4f();

    private Frustum() {}

    public static Frustum getFrustum() {
        return frustum;
    }

    /** Recompute the frustum planes from proj * modelView. */
    public void update(Matrix4f proj, Matrix4f modelView) {
        proj.mul(modelView, clip);
        intersection.set(clip);
    }

    public boolean cubeInFrustum(AABB aabb) {
        return cubeInFrustum(aabb.x0, aabb.y0, aabb.z0, aabb.x1, aabb.y1, aabb.z1);
    }

    public boolean cubeInFrustum(float x1, float y1, float z1, float x2, float y2, float z2) {
        return intersection.testAab(x1, y1, z1, x2, y2, z2);
    }
}
