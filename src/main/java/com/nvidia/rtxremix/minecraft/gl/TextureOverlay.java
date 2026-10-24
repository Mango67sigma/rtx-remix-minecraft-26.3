/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Draws the Remix output image as a full-screen, fully opaque triangle on
 * Minecraft's main render target, after the world and first-person model have
 * been submitted and before the screen effects and HUD.
 *
 * <p>Render thread only. The draw goes through a {@code RenderPass} with its own
 * {@link RenderPipeline} rather than raw GL, so the GL backend's cached state
 * stays valid for everything vanilla draws afterwards. The pipeline neither tests
 * nor writes depth and does not blend: the Remix image replaces vanilla's output.
 */
public class TextureOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextureOverlay.class);

    /** Full-screen triangle from the vanilla screen-quad vertex stage; the fragment stage flips Y. */
    private static final RenderPipeline BLIT = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "pipeline/remix_blit"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "core/remix_blit"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    public TextureOverlay() {
        LOGGER.info("TextureOverlay initialized");
    }

    /**
     * Composites the Remix image onto {@code target}.
     *
     * @param remixColor the interop colour image's view, or null when no image exists this frame
     * @param target     Minecraft's main render target
     */
    public void render(GpuTextureView remixColor, RenderTarget target) {
        if (remixColor == null || target == null) {
            return;
        }
        GpuTextureView colorView = target.getColorTextureView();
        if (colorView == null) {
            return;
        }
        try {
            renderTextureOverlay(remixColor, colorView);
        } catch (Exception e) {
            LOGGER.error("Error rendering texture overlay", e);
        }
    }

    private void renderTextureOverlay(GpuTextureView remixColor, GpuTextureView colorView) {
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "Remix composite", colorView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(BLIT));
            pass.setUniform(
                    "InSampler",
                    remixColor,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * Cleans up resources.
     */
    public void shutdown() {
        LOGGER.info("TextureOverlay shutdown");
    }
}
