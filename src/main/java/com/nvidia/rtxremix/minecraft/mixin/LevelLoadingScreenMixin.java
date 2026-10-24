/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.JoinBurstHold;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the terrain-download screen up until Remix's world-join burst has settled.
 *
 * <p>Since 1.21.9 the download screen is {@code LevelLoadingScreen}, which also
 * covers portal transitions. Vanilla closes it from its tick as soon as the load
 * tracker reports the level ready, which it does once the player's own section is
 * compiled and visible ({@code LevelRenderer.isSectionCompiledAndVisible} runs the
 * tracker's callback); that is while Remix pays its first-sight costs behind the
 * screen. The close is cancelled while {@link JoinBurstHold#keepScreen(long)} says
 * so; vanilla retries every tick, so the screen closes on the first tick after the
 * hold ends. Cancelling at the head also skips the "ready to play" narration until
 * the screen really closes.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {

    private boolean rtxremix$holdBegun;

    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void rtxremix$holdUntilSettled(CallbackInfo ci) {
        JoinBurstHold hold = RemixRenderer.activeJoinBurstHold();
        if (hold == null) {
            return;
        }
        long now = System.nanoTime();
        if (!rtxremix$holdBegun) {
            rtxremix$holdBegun = true;
            hold.begin(now);
        }
        if (hold.keepScreen(now)) {
            ci.cancel();
        }
    }
}
