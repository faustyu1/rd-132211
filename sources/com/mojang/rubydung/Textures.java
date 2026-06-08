package com.mojang.rubydung;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.opengl.GL11;

public class Textures {
    private static final Map<String, Integer> idMap = new HashMap<>();
    private static int lastId = -9_999_999;

    public static int loadTexture(String resourceName, int mode) {
        if (idMap.containsKey(resourceName)) return idMap.get(resourceName);
        try {
            int id = GL11.glGenTextures();
            bind(id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            BufferedImage img = ImageIO.read(Textures.class.getResourceAsStream(resourceName));
            int w = img.getWidth(), h = img.getHeight();
            int[] rawPixels = new int[w * h];
            img.getRGB(0, 0, w, h, rawPixels, 0, w);
            ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
            for (int i = 0; i < rawPixels.length; i++) {
                int a = (rawPixels[i] >> 24) & 0xFF;
                int r = (rawPixels[i] >> 16) & 0xFF;
                int g = (rawPixels[i] >> 8)  & 0xFF;
                int b =  rawPixels[i]         & 0xFF;
                rawPixels[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }
            pixels.asIntBuffer().put(rawPixels);
            pixels.position(0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            MemoryUtil.memFree(pixels);
            idMap.put(resourceName, id);
            return id;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load texture: " + resourceName, e);
        }
    }

    public static void bind(int id) {
        if (id != lastId) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            lastId = id;
        }
    }
}
