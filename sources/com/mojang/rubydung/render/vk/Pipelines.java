package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Builds the shared pipeline layout and all graphics pipelines. Two vertex formats are in
 * play:
 *
 * <ul>
 *   <li>the chunk format, 16 bytes of packed integers plus a per-instance section origin,
 *       used by the two CHUNK_* pipelines;</li>
 *   <li>the streaming format, 44 bytes of floats, used by everything the Tesselator feeds —
 *       UI, text, particles, dropped items, name tags.</li>
 * </ul>
 */
public class Pipelines {
    /** Streaming (Tesselator) vertex: pos3 + uv2 + color4 + light2. */
    public static final int VERTEX_STRIDE = 44;
    /** Packed chunk vertex, see ChunkTesselator. */
    public static final int CHUNK_VERTEX_STRIDE = 16;
    /** Per-instance chunk section origin: three floats. */
    public static final int CHUNK_INSTANCE_STRIDE = 12;

    public enum Pipeline {
        CHUNK_OPAQUE, CHUNK_WATER,
        WORLD_OPAQUE, WORLD_TRANSLUCENT, OVERLAY_3D, LINES, UI, UI_LINES, UI_INVERT
    }

    private static boolean isChunk(Pipeline p) {
        return p == Pipeline.CHUNK_OPAQUE || p == Pipeline.CHUNK_WATER;
    }

    private final VkContext ctx;
    public long pipelineLayout = VK_NULL_HANDLE;
    private final long[] pipelines = new long[Pipeline.values().length];

    private long vertModule = VK_NULL_HANDLE;
    private long fragModule = VK_NULL_HANDLE;
    private long chunkVertModule = VK_NULL_HANDLE;
    private long chunkFragModule = VK_NULL_HANDLE;

    public Pipelines(VkContext ctx, DescriptorAllocator descriptors, int colorFormat, int depthFormat) {
        this.ctx = ctx;
        createLayout(descriptors.setLayout);
        createModules();
        for (Pipeline p : Pipeline.values()) {
            pipelines[p.ordinal()] = build(p, colorFormat, depthFormat);
        }
        vkDestroyShaderModule(ctx.device, vertModule, null);
        vkDestroyShaderModule(ctx.device, fragModule, null);
        vkDestroyShaderModule(ctx.device, chunkVertModule, null);
        vkDestroyShaderModule(ctx.device, chunkFragModule, null);
    }

    public long get(Pipeline p) { return pipelines[p.ordinal()]; }

    private void createLayout(long setLayout) {
        try (MemoryStack stack = stackPush()) {
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                .offset(0)
                .size(128); // proj mat4 (64) + modelView mat4 (64)

            VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(setLayout))
                .pPushConstantRanges(pcRange);
            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(ctx.device, ci, null, pLayout) != VK_SUCCESS)
                throw new RuntimeException("vkCreatePipelineLayout failed");
            pipelineLayout = pLayout.get(0);
        }
    }

    private void createModules() {
        vertModule = createModule(ShaderCompiler.loadVertex("main"));
        fragModule = createModule(ShaderCompiler.loadFragment("main"));
        chunkVertModule = createModule(ShaderCompiler.loadVertex("chunk"));
        chunkFragModule = createModule(ShaderCompiler.loadFragment("chunk"));
    }

    private long createModule(ByteBuffer spv) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spv);
            LongBuffer pModule = stack.mallocLong(1);
            if (vkCreateShaderModule(ctx.device, ci, null, pModule) != VK_SUCCESS)
                throw new RuntimeException("vkCreateShaderModule failed");
            org.lwjgl.system.MemoryUtil.memFree(spv);
            return pModule.get(0);
        }
    }

    private long build(Pipeline p, int colorFormat, int depthFormat) {
        boolean chunk = isChunk(p);
        boolean lines = (p == Pipeline.LINES || p == Pipeline.UI_LINES);
        boolean depthTest = chunk || p == Pipeline.WORLD_OPAQUE || p == Pipeline.WORLD_TRANSLUCENT
                            || p == Pipeline.LINES;
        // Water writes colour but not depth: two water surfaces seen through each other have
        // to both survive the depth test for the blend to mean anything. The renderer draws
        // water sections back to front to keep the blend order right without sorting quads.
        boolean depthWrite = (p == Pipeline.CHUNK_OPAQUE || p == Pipeline.WORLD_OPAQUE
                              || p == Pipeline.WORLD_TRANSLUCENT);
        boolean blend = (p != Pipeline.CHUNK_OPAQUE && p != Pipeline.WORLD_OPAQUE);

        // Chunk geometry has consistent winding and closed blocks, so the far side of every
        // face is genuinely invisible. Water keeps both sides: its surface has to be there
        // when the camera is under it. -Drd.noCull=true disables this if a mesh ever regresses.
        boolean cullBack = p == Pipeline.CHUNK_OPAQUE
                           && !"true".equalsIgnoreCase(System.getProperty("rd.noCull"));

        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(chunk ? chunkVertModule : vertModule)
                .pName(stack.UTF8("main"));
            stages.get(1)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(chunk ? chunkFragModule : fragModule)
                .pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer binding;
            VkVertexInputAttributeDescription.Buffer attrs;
            if (chunk) {
                binding = VkVertexInputBindingDescription.calloc(2, stack);
                binding.get(0).binding(0).stride(CHUNK_VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                binding.get(1).binding(1).stride(CHUNK_INSTANCE_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE);
                attrs = VkVertexInputAttributeDescription.calloc(4, stack);
                // xyz position (1/2048 block) + texture array layer, all packed as uint16
                attrs.get(0).location(0).binding(0).format(VK_FORMAT_R16G16B16A16_UINT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(8);   // color
                attrs.get(2).location(2).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);  // u,v,sky,block
                attrs.get(3).location(3).binding(1).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0); // section origin
            } else {
                binding = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                attrs = VkVertexInputAttributeDescription.calloc(4, stack);
                attrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);   // pos
                attrs.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(12);      // uv
                attrs.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(20);// color
                attrs.get(3).location(3).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(36);      // light
            }

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(binding)
                .pVertexAttributeDescriptions(attrs);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(lines ? VK_PRIMITIVE_TOPOLOGY_LINE_LIST : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                .primitiveRestartEnable(false);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .depthClampEnable(false)
                .rasterizerDiscardEnable(false)
                .polygonMode(lines ? VK_POLYGON_MODE_LINE : VK_POLYGON_MODE_FILL)
                .cullMode(cullBack ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                // The viewport has a negative height so GL-style matrices work unchanged, which
                // also puts screen-space winding back the GL way round: counter-clockwise front.
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .depthBiasEnable(false)
                .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                .sampleShadingEnable(false);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(depthTest)
                .depthWriteEnable(depthWrite)
                .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                .depthBoundsTestEnable(false)
                .stencilTestEnable(false);

            VkPipelineColorBlendAttachmentState.Buffer blendAtt = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(blend);
            if (p == Pipeline.UI_INVERT) {
                // GL's glBlendFunc(GL_ONE_MINUS_DST_COLOR, GL_ZERO): the source colour is
                // multiplied by the inverse of what is already there, so the crosshair stays
                // readable on any terrain instead of turning white-on-white.
                blendAtt.get(0)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
            } else if (blend) {
                blendAtt.get(0)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
            }

            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .logicOpEnable(false)
                .pAttachments(blendAtt);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
                .pColorAttachmentFormats(stack.ints(colorFormat))
                .depthAttachmentFormat(depthFormat);

            VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pNext(renderingInfo)
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState)
                .pRasterizationState(raster)
                .pMultisampleState(multisample)
                .pDepthStencilState(depthStencil)
                .pColorBlendState(colorBlend)
                .pDynamicState(dynamicState)
                .layout(pipelineLayout)
                .renderPass(VK_NULL_HANDLE)
                .subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            if (vkCreateGraphicsPipelines(ctx.device, VK_NULL_HANDLE, ci, null, pPipeline) != VK_SUCCESS)
                throw new RuntimeException("vkCreateGraphicsPipelines failed for " + p);
            return pPipeline.get(0);
        }
    }

    public void destroy() {
        for (long p : pipelines) if (p != VK_NULL_HANDLE) vkDestroyPipeline(ctx.device, p, null);
        if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null);
    }
}
