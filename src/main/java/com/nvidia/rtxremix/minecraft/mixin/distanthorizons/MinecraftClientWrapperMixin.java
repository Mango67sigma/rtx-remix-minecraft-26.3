/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin.distanthorizons;

import com.nvidia.rtxremix.minecraft.lod.LodCapture;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Minecraft's cloud setting as the player left it.
 *
 * <p>The first time Distant Horizons draws, it turns vanilla clouds off so they cannot
 * cover its LOD terrain. The renderer draws its own clouds into the path-traced scene by
 * that setting, so for as long as a renderer that draws LOD terrain exists the setting is
 * left alone, including while path tracing is toggled off and Distant Horizons draws.
 * Follows {@code MinecraftClientWrapperMixin} in kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
@Mixin(MinecraftClientWrapper.class)
public abstract class MinecraftClientWrapperMixin {

    @Inject(method = "disableVanillaClouds", at = @At("HEAD"), cancellable = true)
    private void rtxremix$keepCloudSetting(CallbackInfo ci) {
        if (LodCapture.shared().isAttached()) ci.cancel();
    }
}
