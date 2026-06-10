package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;

/** Shared uint32 index buffer expanding quads (0,1,2,0,2,3) into triangles. */
public class QuadIndexBuffer {
    public static final int MAX_QUADS = 131_072;
    public static final int MAX_INDICES = MAX_QUADS * 6;

    public final VkBuf buffer;

    public QuadIndexBuffer(VkContext ctx) {
        buffer = new VkBuf(ctx, (long) MAX_INDICES * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        ByteBuffer b = buffer.map();
        var ib = b.asIntBuffer();
        for (int q = 0; q < MAX_QUADS; q++) {
            int v = q * 4;
            ib.put(v).put(v + 1).put(v + 2);
            ib.put(v).put(v + 2).put(v + 3);
        }
    }

    public void free() { buffer.free(); }
}
