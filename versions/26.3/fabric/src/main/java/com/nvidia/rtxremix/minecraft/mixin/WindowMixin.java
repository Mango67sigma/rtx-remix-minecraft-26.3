/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.Window;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs the SDL window handle once the window exists. The OpenGL context version
 * the Remix GL interop needs is requested where 26.3 sets the context attributes,
 * in {@code GlBackendMixin}, because the window no longer creates its own
 * context: the GL device creates one shared context before any window.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Shadow
    @Final
    private long handle;

    /** The eight-argument constructor is the one every other constructor delegates to. */
    @Inject(
            method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V",
            at = @At("TAIL"))
    private void rtxremix$onWindowCreated(CallbackInfo ci) {
        RtxRemixMod.LOGGER.info("Window created with SDL handle: {}", handle);
    }
}
