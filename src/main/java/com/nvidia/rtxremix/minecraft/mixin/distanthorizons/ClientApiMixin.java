/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin.distanthorizons;

import com.nvidia.rtxremix.minecraft.lod.LodCapture;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips Distant Horizons' vanilla fade passes while its terrain is captured.
 *
 * <p>Its render events cover only its LOD passes. The fade passes, which it runs from its
 * own {@code LevelRenderer} hooks at {@code executeOutline} and {@code executeOit}, blend
 * the main target toward its LOD image near the border of Minecraft's terrain. While
 * capturing it draws no LOD image, so they would blend in whatever its textures last held,
 * and the Remix composite replaces the main target anyway. Neither pass leaves state its
 * caller reads.
 */
@Mixin(ClientApi.class)
public abstract class ClientApiMixin {

    @Inject(method = "renderFadeOpaque", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipOpaqueFade(CallbackInfo ci) {
        if (LodCapture.shared().isCapturing()) ci.cancel();
    }

    @Inject(method = "renderFadeTransparent", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipTransparentFade(CallbackInfo ci) {
        if (LodCapture.shared().isCapturing()) ci.cancel();
    }
}
