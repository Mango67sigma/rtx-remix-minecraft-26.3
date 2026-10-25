/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.capture.CompileRegion;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;

/**
 * A render region ({@code RenderSectionRegion}, the old {@code RenderChunkRegion}) holds one
 * reference on each of its 27 section snapshots from construction until
 * {@link #rtxremix$release}, which runs exactly once: from the compile task that claimed the
 * region (see {@link RebuildTaskMixin}), or, for a region no task claimed, where vanilla
 * drops its section update ({@link LevelExtractorTerrainMixin}).
 *
 * <p>Regions are constructed on the render thread by {@code RenderRegionCache.createRegion},
 * after the section copies they read were taken, so the capture sequence stamped here orders
 * the snapshots of one section ({@link CompileRegion}).
 */
@Mixin(RenderSectionRegion.class)
public abstract class ChunkRendererRegionMixin implements RegionSnapshotRelease, CompileRegion {

    @Shadow
    @Final
    private SectionCopy[] sections;

    private final AtomicBoolean rtxremix$released = new AtomicBoolean();

    @Unique
    private long rtxremix$captureSequence;

    /** Render thread only: claims and the unclaimed release both run there. */
    @Unique
    private boolean rtxremix$claimed;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$retainSnapshots(ClientLevel level, int minSectionX, int minSectionY, int minSectionZ,
                                          SectionCopy[] regionSections, CallbackInfo ci) {
        for (SectionCopy snapshot : sections) {
            ((RegionSnapshotRelease) snapshot).rtxremix$retain();
        }
        rtxremix$captureSequence = BlockDataQueue.nextCaptureSequence();
    }

    @Override
    public void rtxremix$retain() {
        throw new UnsupportedOperationException("regions are held by exactly one compile task");
    }

    @Override
    public void rtxremix$release() {
        if (!rtxremix$released.compareAndSet(false, true)) {
            return;
        }
        for (SectionCopy snapshot : sections) {
            ((RegionSnapshotRelease) snapshot).rtxremix$release();
        }
    }

    @Override
    public long rtxremix$captureSequence() {
        return rtxremix$captureSequence;
    }

    @Override
    public void rtxremix$claimForTask() {
        rtxremix$claimed = true;
    }

    @Override
    public boolean rtxremix$releaseIfUnclaimed() {
        if (rtxremix$claimed || rtxremix$released.get()) {
            return false;
        }
        rtxremix$release();
        return true;
    }
}
