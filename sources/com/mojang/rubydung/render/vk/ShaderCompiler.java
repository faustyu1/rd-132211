package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Compiles GLSL source to SPIR-V at runtime via shaderc. */
public final class ShaderCompiler {
    private ShaderCompiler() {}

    public static final int VERTEX = shaderc_glsl_vertex_shader;
    public static final int FRAGMENT = shaderc_glsl_fragment_shader;

    /** Reads a shader resource from the classpath and compiles it to a SPIR-V ByteBuffer. */
    public static ByteBuffer compileResource(String resource, int kind) {
        String source = readResource(resource);
        return compile(source, resource, kind);
    }

    public static ByteBuffer compile(String source, String name, int kind) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) throw new RuntimeException("shaderc: failed to init compiler");
        try {
            long options = shaderc_compile_options_initialize();
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

            long result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
            shaderc_compile_options_release(options);

            if (result == 0L) throw new RuntimeException("shaderc: compilation returned null for " + name);

            long status = shaderc_result_get_compilation_status(result);
            if (status != shaderc_compilation_status_success) {
                String log = shaderc_result_get_error_message(result);
                shaderc_result_release(result);
                throw new RuntimeException("shaderc: failed to compile " + name + ":\n" + log);
            }

            ByteBuffer spv = shaderc_result_get_bytes(result);
            // copy out of shaderc-owned memory into a Java-managed direct buffer
            ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc(spv.remaining());
            copy.put(spv);
            copy.flip();
            shaderc_result_release(result);
            return copy;
        } finally {
            shaderc_compiler_release(compiler);
        }
    }

    private static String readResource(String resource) {
        try (InputStream in = ShaderCompiler.class.getResourceAsStream(resource)) {
            if (in == null) throw new RuntimeException("shader resource not found: " + resource);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("failed to read shader " + resource, e);
        }
    }
}
