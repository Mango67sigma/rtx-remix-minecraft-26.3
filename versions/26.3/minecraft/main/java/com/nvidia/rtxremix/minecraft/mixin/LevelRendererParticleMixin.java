/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps vanilla's particle draw off screen while Remix renders the world.
 *
 * <p>The mod draws particles itself from the live particle groups
 * ({@code RemixParticleRenderer}). Vanilla still ticks them and still extracts
 * them into {@code LevelRenderState.particlesRenderState}
 * ({@code LevelExtractor} calls {@code ParticleEngine.extract}); only the
 * submission of that state to the feature collector in
 * {@code LevelRenderer.submitFeatures} is withheld, which is what the 1.21.1
 * redirect of {@code ParticleEngine.render} inside {@code renderLevel} did.
 * The submit only queues nodes on the collector and touches no GL, fog or
 * framebuffer state, so there is nothing to replay, and the extracted state is
 * still cleared by {@code LevelRenderState.reset()} on the next extract.
 * Withholding the whole state also withholds the item-pickup and elder-guardian
 * groups, as the 1.21.1 redirect withheld their {@code CUSTOM} sheet.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererParticleMixin {
    @WrapWithCondition(
            method = "submitFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            ))
    private boolean rtxremix$skipVanillaParticleSubmit(
            ParticlesRenderState particles,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera) {
        return !RtxRemixMod.isRemixWorldRendering();
    }
}
