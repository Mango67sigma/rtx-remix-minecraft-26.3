/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.CompileRegion;
import com.nvidia.rtxremix.minecraft.terrain.capture.CompileTaskRegionOwner;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Releases a compile task's region snapshot exactly once: after the section build that read
 * it, or when the task is cancelled before it ever ran.
 *
 * <p>The 26.x compile task ({@code SectionRenderDispatcher.RenderSection.CompileTask}, the old
 * {@code RebuildTask}) owns its {@code RenderSectionRegion} from construction, which claims it
 * ({@link CompileRegion}), and runs {@code doTask} synchronously on the worker that acquired
 * the buffers (or on the render thread for {@code compileSync}). Most tasks that never run are
 * cancelled ones: {@code createCompileTask} cancels the section's previous task on every
 * recompile, {@code SectionRenderDispatcher.clearCompileQueue} cancels everything queued, and
 * the queue drops cancelled tasks when it polls ({@code SectionTaskDynamicQueue.poll}) while
 * {@code runTask} skips one cancelled after the poll. Each of those paths runs {@code cancel},
 * so releasing there covers them all.
 *
 * <p>The worker and {@code cancel} race for the region through {@link CompileTaskRegionOwner}:
 * whichever claims it first owns the release. A {@code doTask} that loses has run
 * no further than its HEAD, and vanilla's first statement then reads {@code isCancelled},
 * which {@code cancel} set before it took the region, so the task returns {@code CANCELLED}
 * without reading the released snapshot. A {@code cancel} that loses leaves the region to the
 * running build. A task dropped without being cancelled (scheduled on a closed dispatcher)
 * leaves its region to the collector, which only forgoes the reuse.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class RebuildTaskMixin {

    @Shadow
    @Final
    private RenderSectionRegion region;

    @Unique
    private final CompileTaskRegionOwner rtxremix$regionOwner = new CompileTaskRegionOwner();

    @Unique
    private RenderSectionRegion rtxremix$consumed;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$claimRegion(CallbackInfo ci) {
        if ((Object) region instanceof CompileRegion compileRegion) {
            compileRegion.rtxremix$claimForTask();
        }
    }

    @Inject(method = "doTask", at = @At("HEAD"))
    private void rtxremix$captureRegion(SectionBufferBuilderPack buffers,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> cir) {
        rtxremix$consumed = rtxremix$regionOwner.claimForRun() ? region : null;
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

    /** RETURN, so {@code isCancelled} is already set when the region is taken. */
    @Inject(method = "cancel", at = @At("RETURN"))
    private void rtxremix$releaseCancelledRegion(CallbackInfo ci) {
        if (rtxremix$regionOwner.claimForCancel()) {
            ((RegionSnapshotRelease) region).rtxremix$release();
        }
    }
}
