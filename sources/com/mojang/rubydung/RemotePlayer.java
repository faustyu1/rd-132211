package com.mojang.rubydung;

public class RemotePlayer {
    public float x, y, z, yRot, xRot;
    public float xo, yo, zo;
    public String name = "Player";

    public void updateFromNet(float nx, float ny, float nz, float nyRot, float nxRot) {
        xo = x; yo = y; zo = z;
        x = nx; y = ny; z = nz;
        yRot = nyRot; xRot = nxRot;
    }
}
