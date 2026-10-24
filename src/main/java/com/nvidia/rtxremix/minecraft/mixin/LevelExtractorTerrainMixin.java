/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.LevelExtractorTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Terrain scheduling on {@code LevelExtractor}, which since 26.2 owns the section dirty state
 * ({@code SectionUpdateTracker}), the block and section re-flags, the per-frame region cache
 * and vanilla's reload ({@code allChanged}). These hooks are the 26.3 homes of what
 * {@code WorldRendererMixin} did on {@code LevelRenderer} in 1.21.1:
 *
 * <ul>
 *   <li>the water-occlusion margin on block-update rebuilds ({@code setBlocksDirty});</li>
 *   <li>the frame's region cache, whose snapshot references are dropped once every
 *       region of the frame has been created;</li>
 *   <li>the Remix terrain recapture, applied to the view area a reload creates;</li>
 *   <li>resident-only rebuild flags for captures evicted under backpressure.</li>
 * </ul>
 *
 * <p>Everything here runs on the render thread, the only thread that extracts.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorTerrainMixin implements LevelExtractorTerrainAccess {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    private @Nullable SectionUpdateTracker sectionUpdateTracker;

    @Shadow
    private boolean shouldInvalidateCompiledGeometry;

    @Shadow
    public abstract void setSectionDirty(int sectionX, int sectionY, int sectionZ);

    /** Vanilla's cache is a local of extract; captured at construction, released at return. */
    @Unique
    private @Nullable RenderRegionCache rtxremix$frameRegionCache;

    /** Sections to recapture once the pending reload has built its view area. */
    @Unique
    private @Nullable LongSet rtxremix$pendingRecapture;

    @Unique
    private @Nullable ClientLevel rtxremix$pendingRecaptureLevel;

    /**
     * Hidden water-top culling reads farther than vanilla's one-block rebuild margin.
     * Expand horizontal invalidation so a ceiling or fluid update cannot leave a
     * neighbouring section's occlusion decision stale.
     */
    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$expandWaterOcclusionRebuildMargin(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return;
        }

        int radius = Math.max(1, BlockQuadEmitter.getWaterTopOcclusionRadius());
        int minSectionX = SectionPos.blockToSectionCoord(minX - radius);
        int minSectionY = SectionPos.blockToSectionCoord(minY - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ - radius);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX + radius);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ + radius);
        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    setSectionDirty(sectionX, sectionY, sectionZ);
                }
            }
        }
        ci.cancel();
    }

    @WrapOperation(
            method = "extract",
            at = @At(value = "NEW", target = "()Lnet/minecraft/client/renderer/chunk/RenderRegionCache;"))
    private RenderRegionCache rtxremix$captureFrameRegionCache(Operation<RenderRegionCache> original) {
        RenderRegionCache cache = original.call();
        rtxremix$frameRegionCache = cache;
        return cache;
    }

    /**
     * Drops the cache's references so the snapshots it holds can be recycled once the
     * compile tasks of this frame's section updates have run. Every region of the frame
     * was created from the cache in the section-update loop above, and each region holds
     * its own references (see {@code ChunkRendererRegionBuilderMixin}).
     */
    @Inject(method = "extract", at = @At("RETURN"))
    private void rtxremix$releaseFrameRegionCache(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks,
                                                  CallbackInfo ci) {
        RenderRegionCache cache = rtxremix$frameRegionCache;
        rtxremix$frameRegionCache = null;
        rtxremix$releaseRegionCache(cache);
    }

    /**
     * The point where a reload's new view area exists: {@code invalidateCompiledGeometry}
     * has released the old slots, built and repositioned the new ones, and the new section
     * tracker (made by {@code allChanged}) is centred on the camera. A recapture requested
     * with the reload is applied here, before this frame's section updates, so the queued
     * sections are marked clean and vanilla does not queue them a second time.
     */
    @Inject(
            method = "extract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;invalidateCompiledGeometry(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/Options;Lnet/minecraft/client/Camera;Lnet/minecraft/client/color/block/BlockColors;)V",
                    shift = At.Shift.AFTER))
    private void rtxremix$applyPendingRecapture(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks,
                                                CallbackInfo ci) {
        LongSet pending = rtxremix$pendingRecapture;
        ClientLevel pendingLevel = rtxremix$pendingRecaptureLevel;
        rtxremix$pendingRecapture = null;
        rtxremix$pendingRecaptureLevel = null;
        if (pending == null || pendingLevel != level) {
            return;
        }
        ViewArea viewArea = levelRenderer.viewArea();
        if (viewArea != null) {
            rtxremix$queueNow(pending, viewArea);
        }
    }

    /** A recapture belongs to the world it was taken in. */
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void rtxremix$forgetPendingRecapture(@Nullable ClientLevel newLevel, CallbackInfo ci) {
        rtxremix$pendingRecapture = null;
        rtxremix$pendingRecaptureLevel = null;
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        if (level == null || sectionUpdateTracker == null || sections.isEmpty()) {
            return LongSets.emptySet();
        }
        if (shouldInvalidateCompiledGeometry) {
            // allChanged has run but the view area it replaces is still the old one; the
            // new one is built in the next extract. Filter on world state now and apply the
            // queue to the new slots then.
            LongOpenHashSet accepted = new LongOpenHashSet();
            for (LongIterator it = sections.iterator(); it.hasNext(); ) {
                long node = it.nextLong();
                if (sectionUpdateTracker.getDirtyState(node) != null && rtxremix$admit(node, true)) {
                    accepted.add(node);
                }
            }
            rtxremix$pendingRecapture = accepted;
            rtxremix$pendingRecaptureLevel = level;
            return new LongOpenHashSet(accepted);
        }
        ViewArea viewArea = levelRenderer.viewArea();
        if (viewArea == null) {
            return LongSets.emptySet();
        }
        return rtxremix$queueNow(sections, viewArea);
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        // SectionUpdateTracker.setDirty ignores a section outside its window; it never
        // aliases onto another section's state as 1.21.1's ViewArea.setDirty did.
        if (sectionUpdateTracker != null) {
            sectionUpdateTracker.setDirty(cx, sy, cz, false);
        }
    }

    @Unique
    private LongSet rtxremix$queueNow(LongSet sections, ViewArea viewArea) {
        ViewAreaTerrainAccessor slots = (ViewAreaTerrainAccessor) viewArea;
        ArrayList<SectionRenderDispatcher.RenderSection> loadedSections = new ArrayList<>();
        for (LongIterator it = sections.iterator(); it.hasNext(); ) {
            long node = it.nextLong();
            SectionRenderDispatcher.RenderSection section = slots.rtxremix$getRenderSection(node);
            if (section == null) {
                continue;
            }
            boolean uncompiled = section.sectionMesh.get() == CompiledSectionMesh.UNCOMPILED;
            if (rtxremix$admit(node, uncompiled)) {
                loadedSections.add(section);
            }
        }

        // The compile queue picks the task nearest the camera, but queue nearby sections
        // first anyway so visible holes close promptly while the Remix scene repopulates.
        Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        loadedSections.sort(Comparator.comparingDouble(section -> {
            long node = section.getSectionNode();
            double dx = SectionPos.sectionToBlockCoord(SectionPos.x(node)) + 8.0 - camera.x;
            double dy = SectionPos.sectionToBlockCoord(SectionPos.y(node)) + 8.0 - camera.y;
            double dz = SectionPos.sectionToBlockCoord(SectionPos.z(node)) + 8.0 - camera.z;
            return dx * dx + dy * dy + dz * dz;
        }));

        RenderRegionCache cache = new RenderRegionCache();
        LongOpenHashSet queued = new LongOpenHashSet(loadedSections.size());
        try {
            for (SectionRenderDispatcher.RenderSection section : loadedSections) {
                long node = section.getSectionNode();
                section.compileAsync(cache.createRegion(level, node));
                // Keep this frame's section-update loop from queueing the same section a
                // second time when it reaches it in vanilla's visible list.
                SectionUpdateTracker.SectionDirtyState dirtyState = sectionUpdateTracker.getDirtyState(node);
                if (dirtyState != null) {
                    dirtyState.setNotDirty();
                }
                queued.add(node);
            }
        } finally {
            rtxremix$releaseRegionCache(cache);
        }
        return queued;
    }

    /**
     * The gate vanilla applies before it builds a section, plus the old recapture's own: the
     * column is loaded, the section holds blocks, the column's light is on, and a section
     * that has never been compiled has all eight neighbour columns loaded and lit
     * ({@code SectionUpdateTracker.hasAllNeighbors}, which 26.3 checks in the extractor
     * instead of in the compile task). A section built before its light is ready would bake
     * bad light, and marking it clean means nothing re-flags it afterwards.
     */
    @Unique
    private boolean rtxremix$admit(long node, boolean uncompiled) {
        int cx = SectionPos.x(node);
        int sy = SectionPos.y(node);
        int cz = SectionPos.z(node);
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) {
            return false;
        }
        int index = chunk.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= chunk.getSectionsCount() || chunk.getSection(index).hasOnlyAir()) {
            return false;
        }
        if (!level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(node))) {
            return false;
        }
        return !uncompiled || sectionUpdateTracker.hasAllNeighbors(level, node);
    }

    @Unique
    private static void rtxremix$releaseRegionCache(@Nullable RenderRegionCache cache) {
        if ((Object) cache instanceof RegionBuilderRelease release) {
            release.rtxremix$releaseSnapshots();
        }
    }
}
