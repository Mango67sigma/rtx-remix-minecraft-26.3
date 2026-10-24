/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * {@link #rtxremix$release}, which the compile task that consumed it calls exactly once; see
 * {@link RebuildTaskMixin}.
 */
@Mixin(RenderSectionRegion.class)
public abstract class ChunkRendererRegionMixin implements RegionSnapshotRelease {

    @Shadow
    @Final
    private SectionCopy[] sections;

    private final AtomicBoolean rtxremix$released = new AtomicBoolean();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$retainSnapshots(ClientLevel level, int minSectionX, int minSectionY, int minSectionZ,
                                          SectionCopy[] regionSections, CallbackInfo ci) {
        for (SectionCopy snapshot : sections) {
            ((RegionSnapshotRelease) snapshot).rtxremix$retain();
        }
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
}
