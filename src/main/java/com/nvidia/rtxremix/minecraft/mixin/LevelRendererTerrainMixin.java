/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.LevelExtractorTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The terrain side of {@code LevelRenderer} in 26.3: the section slots it owns, exposed to
 * the recapture as {@link WorldRendererTerrainAccess}, and the Tracy/JFR scopes of the
 * section pipeline. The dirty state and the reload live in {@code LevelExtractor}; requests
 * that need them are forwarded to {@link LevelExtractorTerrainMixin}.
 *
 * <p>Profiling scopes keep their 1.21.1 names. {@code WorldRenderer.updateChunks} is
 * {@code compileSections}; its upload phase, {@code uploadTerrainBuffersToGpu}, now runs
 * after it in {@code render} and is a sibling scope rather than a child; and
 * {@code WorldRenderer.setupTerrain} is the section occlusion update, the part of 1.21.1's
 * {@code setupRender} that still runs in the render pass (the frustum cull moved into
 * {@code LevelExtractor.applyFrustum}).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTerrainMixin implements WorldRendererTerrainAccess {

    @Shadow
    private @Nullable ViewArea viewArea;

    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    // compileSections, its synchronous compiles and the upload all run on the render
    // thread and do not nest in themselves, so one field per scope is enough.
    @Unique
    private @Nullable ProfileScope rtxremix$compileSectionsScope;

    @Unique
    private @Nullable ProfileScope rtxremix$syncCompileScope;

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        LongOpenHashSet built = new LongOpenHashSet();
        if (viewArea == null) {
            return built;
        }
        // forEach, not the iterator: RotatingSectionStorage's iterator stops one slot short.
        ((ViewAreaTerrainAccessor) viewArea).rtxremix$sections().forEach(section -> {
            if (section.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED) {
                built.add(section.getSectionNode());
            }
        });
        return built;
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        if (sections.isEmpty()) {
            return LongSets.emptySet();
        }
        return ((LevelExtractorTerrainAccess) Minecraft.getInstance().levelExtractor)
                .rtxremix$queueSectionsForRemix(sections);
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        ((LevelExtractorTerrainAccess) Minecraft.getInstance().levelExtractor)
                .rtxremix$scheduleResidentSectionRebuild(cx, sy, cz);
    }

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void rtxremix$compileSectionsHead(CameraRenderState camera, CallbackInfo ci) {
        int queued = rtxremix$compileQueueSize();
        if (queued >= 0) {
            ProfileScope.plot("Java.Chunk.toBatchCount", queued);
        }
        if (ProfileScope.isDeepProfilingEnabled()) {
            ProfileScope scope = ProfileScope.begin("WorldRenderer.updateChunks");
            if (queued >= 0) {
                scope.dirtyLeaves(queued);
            }
            rtxremix$compileSectionsScope = scope;
        }
    }

    @Inject(method = "compileSections", at = @At("RETURN"))
    private void rtxremix$compileSectionsReturn(CameraRenderState camera, CallbackInfo ci) {
        ProfileScope scope = rtxremix$compileSectionsScope;
        rtxremix$compileSectionsScope = null;
        if (scope != null) {
            scope.close();
        }
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"))
    private void rtxremix$beforeSyncCompile(CameraRenderState camera, CallbackInfo ci) {
        if (ProfileScope.isDeepProfilingEnabled()) {
            ProfileScope scope = ProfileScope.begin("WorldRenderer.updateChunks.build_near_sync");
            int queued = rtxremix$compileQueueSize();
            if (queued >= 0) {
                scope.dirtyLeaves(queued);
            }
            rtxremix$syncCompileScope = scope;
        }
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V",
                    shift = At.Shift.AFTER))
    private void rtxremix$afterSyncCompile(CameraRenderState camera, CallbackInfo ci) {
        ProfileScope scope = rtxremix$syncCompileScope;
        rtxremix$syncCompileScope = null;
        if (scope != null) {
            scope.close();
        }
    }

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"))
    private void rtxremix$scopeTerrainUpload(SectionRenderDispatcher dispatcher, Operation<Void> original) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            original.call(dispatcher);
            return;
        }
        try (ProfileScope ignored = ProfileScope.begin("WorldRenderer.updateChunks.upload")) {
            original.call(dispatcher);
        }
    }

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"))
    private void rtxremix$scopeSectionOcclusionUpdate(SectionOcclusionGraph graph, CameraRenderState camera, int fov,
                                                      ChunkLoadingRenderState chunkLoading, Operation<Void> original) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            original.call(graph, camera, fov, chunkLoading);
            return;
        }
        try (ProfileScope ignored = ProfileScope.beginJfrOnly("WorldRenderer.setupTerrain")
                .plotOnClose("Java.WorldRenderer.setupTerrain.ms")) {
            original.call(graph, camera, fov, chunkLoading);
        }
    }

    @Unique
    private int rtxremix$compileQueueSize() {
        return sectionRenderDispatcher == null ? -1 : sectionRenderDispatcher.getCompileQueueSize();
    }
}
