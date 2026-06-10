package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.VK10.*;

/** Builds the shared pipeline layout and all graphics pipelines. Vertex format: pos3+uv2+color4 (36B). */
public class Pipelines {
    public static final int VERTEX_STRIDE = 36; // 9 floats

    public enum Pipeline { WORLD_OPAQUE, WORLD_TRANSLUCENT, OVERLAY_3D, LINES, UI, UI_LINES }

    private final VkContext ctx;
    public long pipelineLayout = VK_NULL_HANDLE;
    private final long[] pipelines = new long[Pipeline.values().length];

    private long vertModule = VK_NULL_HANDLE;
    private long fragModule = VK_NULL_HANDLE;

    public Pipelines(VkContext ctx, DescriptorAllocator descriptors, int colorFormat, int depthFormat) {
        this.ctx = ctx;
        createLayout(descriptors.setLayout);
        createModules();
        for (Pipeline p : Pipeline.values()) {
            pipelines[p.ordinal()] = build(p, colorFormat, depthFormat);
        }
        // modules can be destroyed after pipeline creation
        vkDestroyShaderModule(ctx.device, vertModule, null);
        vkDestroyShaderModule(ctx.device, fragModule, null);
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
        ByteBuffer vertSpv = ShaderCompiler.compileResource("/shaders/main.vert", ShaderCompiler.VERTEX);
        ByteBuffer fragSpv = ShaderCompiler.compileResource("/shaders/main.frag", ShaderCompiler.FRAGMENT);
        vertModule = createModule(vertSpv);
        fragModule = createModule(fragSpv);
        org.lwjgl.system.MemoryUtil.memFree(vertSpv);
        org.lwjgl.system.MemoryUtil.memFree(fragSpv);
    }

    private long createModule(ByteBuffer spv) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spv);
            LongBuffer pModule = stack.mallocLong(1);
            if (vkCreateShaderModule(ctx.device, ci, null, pModule) != VK_SUCCESS)
                throw new RuntimeException("vkCreateShaderModule failed");
            return pModule.get(0);
        }
    }

    private long build(Pipeline p, int colorFormat, int depthFormat) {
        boolean lines = (p == Pipeline.LINES || p == Pipeline.UI_LINES);
        boolean depthTest = (p == Pipeline.WORLD_OPAQUE || p == Pipeline.WORLD_TRANSLUCENT || p == Pipeline.LINES);
        // layer 1 (WORLD_TRANSLUCENT) carries water AND shaded opaque faces; it must write
        // depth so those opaque faces occlude geometry behind them (otherwise caves show
        // through solid blocks). Water alpha (0.65) tolerates depth-write fine here.
        boolean depthWrite = (p == Pipeline.WORLD_OPAQUE || p == Pipeline.WORLD_TRANSLUCENT);
        boolean blend = (p != Pipeline.WORLD_OPAQUE);

        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(vertModule)
                .pName(stack.UTF8("main"));
            stages.get(1)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragModule)
                .pName(stack.UTF8("main"));

            // vertex input: one interleaved binding, 3 attributes
            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0).stride(VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(3, stack);
            attrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);   // pos
            attrs.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(12);      // uv
            attrs.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(20);// color

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
                .cullMode(VK_CULL_MODE_NONE)
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
            if (blend) {
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
