package com.mojang.rubydung.render.vk;

import java.io.InputStream;
import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

/**
 * Loads the SPIR-V modules the pipelines are built from.
 *
 * The shaders used to be compiled from GLSL on every start with shaderc, which meant
 * shipping ~10 MB of compiler natives per platform and paying the compile on the way to the
 * main menu, to produce the same bytes every time. They are compiled ahead of time by
 * {@code tools/compile-shaders.sh} into {@code resources/shaders/*.spv} instead; the game
 * only reads them. Editing a shader means re-running that script, which is also the only
 * thing that still needs the shaderc dependency.
 */
public final class ShaderCompiler {
    private ShaderCompiler() {}

    public static ByteBuffer loadVertex(String name) { return load("/shaders/" + name + ".vert.spv"); }
    public static ByteBuffer loadFragment(String name) { return load("/shaders/" + name + ".frag.spv"); }

    /** Reads a SPIR-V resource into a native buffer. The caller frees it. */
    public static ByteBuffer load(String resource) {
        try (InputStream in = ShaderCompiler.class.getResourceAsStream(resource)) {
            if (in == null)
                throw new RuntimeException("SPIR-V resource not found: " + resource
                    + " — run tools/compile-shaders.sh to regenerate it from the GLSL source");
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0 || bytes.length % 4 != 0)
                throw new RuntimeException("malformed SPIR-V in " + resource + ": " + bytes.length + " bytes");
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
            buf.put(bytes).flip();
            return buf;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("failed to read " + resource, e);
        }
    }
}
