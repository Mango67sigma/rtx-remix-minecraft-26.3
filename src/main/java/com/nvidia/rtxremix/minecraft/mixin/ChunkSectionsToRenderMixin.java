/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.VanillaTerrainDraw;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps vanilla's terrain off the screen while Remix draws the world.
 *
 * <p>Vanilla 26.3 draws its compiled sections from {@code LevelRenderer}'s main
 * frame-graph pass through {@code ChunkSectionsToRender.renderGroup} (the opaque
 * group, then the translucent group in classic transparency) and {@code
 * renderOit} (the translucent group under improved transparency). Those are the
 * descendants of the 1.21.1 {@code renderSectionLayer} the mod cancelled; each is
 * cancelled at its head, which leaves the render pass exactly as it was, so the
 * visibility walk, section compiles and uploads around them keep running. The
 * main traversal itself is never cancelled.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaTerrainGroup(CallbackInfo ci) {
        if (VanillaTerrainDraw.skipVanillaTerrain()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaOitTerrain(CallbackInfo ci) {
        if (VanillaTerrainDraw.skipVanillaTerrain()) {
            ci.cancel();
        }
    }
}
