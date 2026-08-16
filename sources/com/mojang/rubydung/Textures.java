package com.mojang.rubydung;

import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.VkTexture;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * PNG loading, via stb_image rather than ImageIO. AWT is a heavyweight dependency for a
 * Vulkan game to drag in for the sake of decoding two images, and pulling it in also means
 * the headless and dock-icon workarounds that used to surround it.
 */
public class Textures {
    private static final Map<String, VkTexture> cache = new HashMap<>();

    /** Load a PNG resource into a Vulkan texture. linear=false uses NEAREST filtering. */
    public static VkTexture loadTexture(String resourceName, boolean linear) {
        VkTexture cached = cache.get(resourceName);
        if (cached != null) return cached;
        Pixels px = readPixels(resourceName);
        VkTexture tex = GameRenderer.instance.createTexture(px.w, px.h, px.rgba, linear);
        STBImage.stbi_image_free(px.rgba);
        cache.put(resourceName, tex);
        return tex;
    }

    /**
     * Load a tile sheet as a mipmapped texture array, one layer per tile, read left to right
     * and top to bottom — the same numbering {@code Tile} uses for its texture indices.
     */
    public static VkTexture loadTileArray(String resourceName, int tileW, int tileH) {
        String key = resourceName + "@" + tileW + "x" + tileH;
        VkTexture cached = cache.get(key);
        if (cached != null) return cached;
        Pixels px = readPixels(resourceName);
        VkTexture tex = GameRenderer.instance.createTileArray(px.rgba, px.w, px.h, tileW, tileH);
        STBImage.stbi_image_free(px.rgba);
        cache.put(key, tex);
        return tex;
    }

    private record Pixels(ByteBuffer rgba, int w, int h) {}

    /** Decode a classpath PNG to tightly packed RGBA8. The buffer is stb-owned. */
    private static Pixels readPixels(String resourceName) {
        ByteBuffer encoded = readResource(resourceName);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
            ByteBuffer rgba = STBImage.stbi_load_from_memory(encoded, w, h, channels, 4);
            if (rgba == null)
                throw new RuntimeException("failed to decode " + resourceName + ": " + STBImage.stbi_failure_reason());
            return new Pixels(rgba, w.get(0), h.get(0));
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    private static ByteBuffer readResource(String resourceName) {
        try (InputStream in = Textures.class.getResourceAsStream(resourceName)) {
            if (in == null) throw new RuntimeException("Texture resource not found: " + resourceName);
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
            buf.put(bytes).flip();
            return buf;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load texture: " + resourceName, e);
        }
    }
}
