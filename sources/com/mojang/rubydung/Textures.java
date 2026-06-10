package com.mojang.rubydung;

import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.VkTexture;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;

public class Textures {
    private static final Map<String, VkTexture> cache = new HashMap<>();

    /** Load a PNG resource into a Vulkan texture. linear=false uses NEAREST filtering. */
    public static VkTexture loadTexture(String resourceName, boolean linear) {
        VkTexture cached = cache.get(resourceName);
        if (cached != null) return cached;
        try {
            BufferedImage img = ImageIO.read(Textures.class.getResourceAsStream(resourceName));
            int w = img.getWidth(), h = img.getHeight();
            int[] rawPixels = new int[w * h];
            img.getRGB(0, 0, w, h, rawPixels, 0, w);
            ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
            for (int p : rawPixels) {
                int a = (p >> 24) & 0xFF;
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8)  & 0xFF;
                int b =  p        & 0xFF;
                // RGBA byte order for VK_FORMAT_R8G8B8A8_UNORM
                pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
            }
            pixels.flip();
            VkTexture tex = GameRenderer.instance.createTexture(w, h, pixels, linear);
            MemoryUtil.memFree(pixels);
            cache.put(resourceName, tex);
            return tex;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load texture: " + resourceName, e);
        }
    }
}
