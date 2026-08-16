import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Compiles every GLSL shader under a directory to SPIR-V, writing {@code <name>.<stage>.spv}
 * beside the source. This is the only thing in the project that needs shaderc, and it runs
 * at build time rather than on every start — see {@code tools/compile-shaders.sh}.
 */
public final class ShaderBuild {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "resources/shaders");
        List<Path> sources = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".vert") || n.endsWith(".frag");
            }).sorted().forEach(sources::add);
        }
        if (sources.isEmpty()) {
            System.err.println("no .vert/.frag files under " + dir);
            System.exit(1);
        }

        long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) throw new RuntimeException("shaderc: failed to init compiler");
        int failed = 0;
        try {
            for (Path src : sources) {
                String name = src.getFileName().toString();
                int kind = name.endsWith(".vert") ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader;
                String source = Files.readString(src, StandardCharsets.UTF_8);

                long options = shaderc_compile_options_initialize();
                shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
                long result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
                shaderc_compile_options_release(options);

                if (result == 0L) {
                    System.err.println(name + ": compilation returned null");
                    failed++;
                    continue;
                }
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    System.err.println(name + ":\n" + shaderc_result_get_error_message(result));
                    shaderc_result_release(result);
                    failed++;
                    continue;
                }

                ByteBuffer spv = shaderc_result_get_bytes(result);
                byte[] bytes = new byte[spv.remaining()];
                spv.get(bytes);
                shaderc_result_release(result);

                File out = new File(src.toString() + ".spv");
                Files.write(out.toPath(), bytes);
                System.out.println("  " + name + " -> " + out.getName() + " (" + bytes.length + " bytes)");
            }
        } finally {
            shaderc_compiler_release(compiler);
        }
        if (failed > 0) System.exit(1);
    }
}
