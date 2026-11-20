/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import com.nvidia.rtxremix.minecraft.benchmark.PlaybackFramerateLimit;
import com.nvidia.rtxremix.minecraft.camera.CameraController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies the mod's frame-rate policy, {@link PlaybackFramerateLimit}, on top of vanilla's.
 *
 * <p>Since 1.21.2 the limit is owned by {@code FramerateLimitTracker}, which
 * {@code Minecraft.renderFrame} reads once per frame into the game render state
 * and feeds to {@code FramerateLimiter.limitDisplayFPS}. Vanilla drops an
 * iconified window to 10 fps and an idle one to 30 or 10 after 60 s or 10 min
 * without input. Two adjustments follow:
 * <ul>
 *   <li>A benchmark, still-shot run or camera path playback is unattended, so it
 *       gets the configured Max Framerate (the private {@code framerateLimit})
 *       whatever the idle clock says; only the iconified throttle still applies.</li>
 *   <li>While Remix renders, an unfocused but visible window, which vanilla leaves
 *       at full rate, is capped by {@link UnfocusedFramerateLimit} so a game left
 *       behind other windows does not hold the GPU at path-tracing load, exactly as
 *       the 1.21.1 port capped {@code Minecraft.getFramerateLimit}. A running
 *       benchmark is exempt.</li>
 * </ul>
 * {@code rtx.conf} enables {@code rtx.enableInputQueuePing}, which keeps the
 * window responsive at every setting on the Remix present path.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private int framerateLimit;

    @Shadow
    public abstract FramerateLimitTracker.FramerateThrottleReason getThrottleReason();

    @ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
    private int rtxremix$applyRemixFramerate(int limit) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer == null) {
            return limit;
        }
        boolean benchmarkRunning = renderer.getBenchmarkController().isRunning();
        boolean pathPlaying = renderer.getCameraController().state() == CameraController.State.PLAYING;
        boolean windowActive = minecraft.isWindowActive();
        boolean remixRendering = renderer.isInitialized();
        if (!benchmarkRunning && !pathPlaying && (windowActive || !remixRendering)) {
            return limit;
        }
        boolean iconified = getThrottleReason() == FramerateLimitTracker.FramerateThrottleReason.WINDOW_ICONIFIED;
        return PlaybackFramerateLimit.resolve(
                limit, framerateLimit, iconified, windowActive, remixRendering,
                benchmarkRunning, pathPlaying, UnfocusedFramerateLimit.get());
    }
}
