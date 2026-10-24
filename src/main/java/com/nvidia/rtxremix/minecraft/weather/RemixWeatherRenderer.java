/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws rain and snow onto the composited Remix colour image, depth-testing
 * against the interop depth texture so weather is occluded by path-traced
 * geometry.
 *
 * <p>Vanilla 26.3 extracts the weather columns, builds them into a GPU vertex
 * buffer in {@code WeatherEffectRenderer.prepare} and draws them inside the main
 * frame-graph pass. While Remix renders the world, {@code
 * WeatherEffectRendererMixin} captures that buffer and the column counts here
 * and cancels vanilla's draw; this renderer draws the same vertices later in the
 * frame, after the Remix image has been composited, through its own {@code
 * RenderPass}: the depth buffer is first seeded from the Remix depth, then rain
 * is drawn with the refraction pipeline over the Remix image and snow with
 * vanilla's weather pipeline. Render thread only.
 *
 * <p>Minecraft 26.x is reverse-Z. The seed writes {@code 1 - remixDepth} and the
 * weather projection is a vanilla {@link Projection} (planes swapped), which maps
 * a conventional depth {@code d} to exactly {@code 1 - d}, so the
 * GREATER_THAN_OR_EQUAL test reproduces the LEQUAL test of the 1.21.1 port.
 */
public final class RemixWeatherRenderer {
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;
    private static final int INDICES_PER_COLUMN = 6;

    /** Writes the Remix depth into the attached depth buffer; depth-only pass, always passes. */
    private static final RenderPipeline DEPTH_SEED = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "pipeline/remix_depth_seed"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "core/remix_depth_seed"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
            .build();

    /**
     * Rain over the Remix image: refraction and reflection sampled from Sampler1.
     * Colour blends by coverage, alpha is replaced, no depth write, no culling.
     */
    private static final RenderPipeline RAIN = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "pipeline/remix_rain"))
            .withVertexShader(Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "core/rain_refraction"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "core/rain_refraction"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(new BlendFunction(
                    BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO)))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();

    /** Snow keeps vanilla's weather pipeline: particle shader, translucent blend, no depth write. */
    private static final RenderPipeline SNOW = RenderPipelines.WEATHER;

    // The frame's vanilla weather geometry, borrowed from WeatherEffectRenderer: it
    // replaces the buffer only in its next prepare, which is the next frame.
    private GpuBuffer vertexBuffer;
    private AbstractTexture rainTexture;
    private AbstractTexture snowTexture;
    private int rainColumns;
    private int snowColumns;

    private final Matrix4f modelView = new Matrix4f();
    private final Projection projection = new Projection();
    private ProjectionMatrixBuffer projectionBuffer;
    private boolean cameraReady;
    private int lastDrawCount;
    private int lastQuadCount;

    /** Forgets the previous frame's capture; called at the start of every level render. */
    public void beginFrame() {
        vertexBuffer = null;
        rainTexture = null;
        snowTexture = null;
        rainColumns = 0;
        snowColumns = 0;
        lastDrawCount = 0;
        lastQuadCount = 0;
    }

    /**
     * Records the geometry vanilla prepared this frame. Rain columns come first in
     * the buffer, snow columns after, four vertices each, as {@code
     * WeatherEffectRenderer.prepareInstances} wrote them.
     */
    public void capture(GpuBuffer vanillaVertexBuffer,
                        AbstractTexture rain,
                        AbstractTexture snow,
                        int rainColumnCount,
                        int snowColumnCount) {
        vertexBuffer = vanillaVertexBuffer;
        rainTexture = rain;
        snowTexture = snow;
        rainColumns = rainColumnCount;
        snowColumns = snowColumnCount;
    }

    /** Builds the weather view and projection from the frame's camera; width and height are the render target's. */
    public void updateCamera(Camera camera, float fovYDegrees, int width, int height) {
        camera.getViewRotationMatrix(modelView);
        projection.setupPerspective(NEAR_PLANE, FAR_PLANE, fovYDegrees, width, height);
        cameraReady = true;
    }

    /**
     * Draws the captured weather onto {@code target}.
     *
     * @param remixColor the composited Remix image, sampled by the rain refraction
     * @param remixDepth the Remix primary depth that seeds the depth test
     */
    public void render(GpuTextureView remixColor, GpuTextureView remixDepth, RenderTarget target) {
        int columns = rainColumns + snowColumns;
        if (columns == 0 || vertexBuffer == null) {
            return;
        }
        if (remixDepth == null) {
            throw new IllegalStateException("Remix depth texture is unavailable");
        }
        if (remixColor == null) {
            throw new IllegalStateException("Remix final-color texture is unavailable");
        }
        if (!cameraReady) {
            throw new IllegalStateException("Weather camera state was not initialized");
        }
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }

        seedDepth(remixDepth, depthView);
        lastDrawCount = (rainColumns > 0 ? 1 : 0) + (snowColumns > 0 ? 1 : 0);
        lastQuadCount = columns;
        drawWeather(remixColor, colorView, depthView);
    }

    private void seedDepth(GpuTextureView remixDepth, GpuTextureView depthView) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Remix depth seed")
                .withDepthAttachment(depthView)
                .build();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(DEPTH_SEED));
            pass.setUniform("InSampler", remixDepth, nearest);
            pass.draw(3, 1, 0, 0);
        }
    }

    private void drawWeather(GpuTextureView remixColor, GpuTextureView colorView, GpuTextureView depthView) {
        if (projectionBuffer == null) {
            projectionBuffer = new ProjectionMatrixBuffer("remix weather");
        }
        Minecraft client = Minecraft.getInstance();
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer((rainColumns + snowColumns) * INDICES_PER_COLUMN);

        RenderSystem.backupProjectionMatrix();
        try {
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView);
            try (RenderPass pass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> "Remix weather", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.setVertexBuffer(0, vertexBuffer.slice());
                if (rainColumns > 0 && rainTexture != null) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(RAIN));
                    pass.setUniform("Sampler0", rainTexture.getTextureView(), rainTexture.getSampler());
                    pass.setUniform("Sampler1", remixColor, linear);
                    pass.drawIndexed(rainColumns * INDICES_PER_COLUMN, 1, 0, 0, 0);
                }
                if (snowColumns > 0 && snowTexture != null) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(SNOW));
                    pass.setUniform("Sampler0", snowTexture.getTextureView(), snowTexture.getSampler());
                    pass.setUniform("Sampler2", client.gameRenderer.levelLightmap(), linear);
                    pass.drawIndexed(snowColumns * INDICES_PER_COLUMN, 1, rainColumns * INDICES_PER_COLUMN, 0, 0);
                }
            }
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
    }

    public int getLastDrawCount() {
        return lastDrawCount;
    }

    public int getLastQuadCount() {
        return lastQuadCount;
    }

    /** Drops the borrowed geometry and the projection buffer; render thread. */
    public void cleanup() {
        beginFrame();
        cameraReady = false;
        if (projectionBuffer != null) {
            projectionBuffer.close();
            projectionBuffer = null;
        }
    }
}
