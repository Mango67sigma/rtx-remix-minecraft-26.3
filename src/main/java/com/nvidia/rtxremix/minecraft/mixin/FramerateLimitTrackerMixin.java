/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies {@link UnfocusedFramerateLimit} while the window is unfocused, so a
 * game left behind other windows does not hold the GPU at path-tracing load.
 * Without an initialized Remix renderer the game draws through vanilla, so
 * the limit is left to vanilla too.
 *
 * <p>Since 1.21.2 the limit is owned by {@code FramerateLimitTracker}, which
 * {@code Minecraft.renderFrame} reads once per frame into the game render state
 * and feeds to {@code FramerateLimiter.limitDisplayFPS}. Vanilla already drops an
 * iconified window to 10 fps and an idle one to 30 or 10; this cap covers the
 * unfocused, visible window vanilla leaves at full rate, exactly as the 1.21.1
 * port capped {@code Minecraft.getFramerateLimit}. {@code rtx.conf} enables
 * {@code rtx.enableInputQueuePing}, which keeps the window responsive at every
 * setting on the Remix present path.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
    private int rtxremix$capWhileUnfocused(int limit) {
        if (minecraft.isWindowActive()) {
            return limit;
        }
        // A benchmark measures renderer throughput and must not be throttled
        // because the user tabbed away while it ran.
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer == null || !renderer.isInitialized()
                || renderer.getBenchmarkController().isRunning()) {
            return limit;
        }
        return UnfocusedFramerateLimit.apply(limit);
    }
}
