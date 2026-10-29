/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin.distanthorizons;

import com.nvidia.rtxremix.minecraft.lod.LodCapture;
import com.nvidia.rtxremix.minecraft.lod.LodSection;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * Follows one Distant Horizons render section for the LOD capture: the vertex buffers it
 * is about to upload, whether its quadtree draws it, and its closing.
 *
 * <p>{@code uploadToGpuAsync} runs on Distant Horizons' render-loading threads with the
 * section's freshly built buffers, which return to Distant Horizons' pool once the upload
 * completes, so the capture copies them at its head. {@code setRenderingEnabled} is what
 * the quadtree calls for every node it decides on each tick, true for the sections in its
 * new draw list and false for the rest, so only a change is published. {@code close}
 * disables the section itself after its head.
 *
 * <p>The anchors follow {@code LodRenderSectionMixin} in
 * kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
@Mixin(LodRenderSection.class)
public abstract class LodRenderSectionMixin {

    @Shadow @Final public long pos;
    @Shadow @Final private IClientLevelWrapper clientLevelWrapper;
    @Shadow private boolean renderingEnabled;

    @Unique
    private LodSection rtxremix$section;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rtxremix$track(CallbackInfo ci) {
        rtxremix$section = new LodSection(LodCapture.shared(),
                clientLevelWrapper.getWrappedMcObject(),
                DhSectionPos.getMinCornerBlockX(pos),
                clientLevelWrapper.getMinHeight(),
                DhSectionPos.getMinCornerBlockZ(pos),
                DhSectionPos.getBlockWidth(pos));
    }

    @Inject(method = "uploadToGpuAsync", at = @At("HEAD"))
    private void rtxremix$captureGeometry(CompletableFuture<Void> parentFuture,
                                          ArrayList<ByteBuffer> opaqueBuffers,
                                          ArrayList<ByteBuffer> transparentBuffers,
                                          CallbackInfoReturnable<CompletableFuture<LodBufferContainer>> cir) {
        rtxremix$section.publishGeometry(opaqueBuffers, transparentBuffers);
    }

    @Inject(method = "setRenderingEnabled", at = @At("HEAD"))
    private void rtxremix$followEnabled(boolean enabled, CallbackInfo ci) {
        if (enabled != renderingEnabled) rtxremix$section.publishEnabled(enabled);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rtxremix$followClose(CallbackInfo ci) {
        rtxremix$section.publishClosed();
    }
}
