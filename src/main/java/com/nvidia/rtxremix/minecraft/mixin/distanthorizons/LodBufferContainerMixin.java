/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin.distanthorizons;

import com.nvidia.rtxremix.minecraft.lod.LodCapture;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Keeps Distant Horizons from holding a GPU copy of terrain it does not draw.
 *
 * <p>{@code uploadBuffersAsync} queues the vertex and index buffer uploads of one buffer
 * set on Distant Horizons' render thread and returns their combined future. While
 * capturing, it returns an already completed future instead: the container still completes
 * and counts as uploaded, which is what the quadtree waits on before it swaps detail, but
 * its buffer wrappers stay empty. Ending capture rebuilds every section with real buffers.
 * Follows {@code LodBufferContainerMixin} in kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
@Mixin(LodBufferContainer.class)
public abstract class LodBufferContainerMixin {

    @Inject(method = "uploadBuffersAsync", at = @At("HEAD"), cancellable = true)
    private static void rtxremix$skipUpload(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (LodCapture.shared().isCapturing()) cir.setReturnValue(CompletableFuture.completedFuture(null));
    }
}
