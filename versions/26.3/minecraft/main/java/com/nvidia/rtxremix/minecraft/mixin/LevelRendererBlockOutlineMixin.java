/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockOutlinePass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the targeted-block outline vanilla submits, for the Remix ribbon that
 * replaces its lines in the traced scene.
 *
 * <p>Every feature submit starts by clearing the previous frame's outline, so a frame
 * in which vanilla submits none, because the HUD is hidden or the player may not
 * build here, submits none to Remix either. Vanilla's own line submit is left in
 * place; it lands in the GL frame under Remix's image, as it did before.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererBlockOutlineMixin {

    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void rtxremix$beginBlockOutlineFrame(
            LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector,
            boolean renderOutline, CallbackInfo ci) {
        RemixBlockOutlinePass.beginActiveFrame();
    }

    @Inject(method = "submitBlockOutline", at = @At("HEAD"))
    private void rtxremix$captureBlockOutline(
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
            LevelRenderState levelRenderState, CallbackInfo ci) {
        if (RtxRemixMod.isRtxEnabled()) {
            RemixBlockOutlinePass.captureActive(levelRenderState);
        }
    }
}
