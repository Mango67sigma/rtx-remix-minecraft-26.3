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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Releases a compile task's region snapshot once the section build that read it has
 * returned. The 26.x compile task ({@code SectionRenderDispatcher.RenderSection.CompileTask},
 * the old {@code RebuildTask}) owns its {@code RenderSectionRegion} from construction and runs
 * {@code doTask} synchronously on the worker that acquired the buffers; the worker is the
 * region's only reader. {@code cancel} on the render thread merely flips a flag, so the region
 * captured at the start of {@code doTask} is owned by this worker whichever branch vanilla
 * takes, including the early returns that never read it. A task cancelled before it ever runs
 * leaves its region to the collector, which only forgoes the reuse.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class RebuildTaskMixin {

    @Shadow
    @Final
    private RenderSectionRegion region;

    private RenderSectionRegion rtxremix$consumed;

    @Inject(method = "doTask", at = @At("HEAD"))
    private void rtxremix$captureRegion(SectionBufferBuilderPack buffers,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> cir) {
        rtxremix$consumed = region;
    }

    @Inject(method = "doTask", at = @At("RETURN"))
    private void rtxremix$releaseRegion(SectionBufferBuilderPack buffers,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> cir) {
        RenderSectionRegion consumed = rtxremix$consumed;
        rtxremix$consumed = null;
        if (consumed != null) {
            ((RegionSnapshotRelease) consumed).rtxremix$release();
        }
    }
}
