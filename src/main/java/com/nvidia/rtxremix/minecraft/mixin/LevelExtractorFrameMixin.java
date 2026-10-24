/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the start of the frame's world work.
 *
 * <p>Since 26.1 a frame extracts the level into render states ({@code
 * LevelExtractor.extract}, called from {@code GameRenderer.extract}) before
 * {@code LevelRenderer.render} draws them. The 1.21.1 port began its frame at
 * Fabric's {@code WorldRenderEvents.START}, the first world work of the frame;
 * the extraction head is that point now, and it runs only on frames that will
 * render the level. The hub applies pending texture and material reloads here,
 * before vanilla extracts anything the reload would invalidate.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorFrameMixin {

    @Inject(
            method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
            at = @At("HEAD"))
    private void rtxremix$beginWorldFrame(CallbackInfo ci) {
        RtxRemixMod.onLevelExtractionStart();
    }
}
