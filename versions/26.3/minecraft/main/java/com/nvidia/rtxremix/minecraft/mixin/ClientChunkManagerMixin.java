/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.ClientChunkRing;
import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import com.nvidia.rtxremix.minecraft.terrain.SkippedSectionRebuilds;
import com.nvidia.rtxremix.minecraft.terrain.LocalChunkBatchPacing;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Section rebuild bookkeeping raised by the client chunk manager while RTX capture is on,
 * and the chunk unloads its ring leaves unreported.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkManagerMixin {

    /**
     * Drops section rebuilds requested only because the client light engine changed a
     * section's light.
     *
     * <p>Vanilla re-meshes such a section so its baked lightmap follows; in 26.3 the request
     * reaches {@code LevelExtractor.setSectionDirty} from here. While RTX is enabled the
     * section builder captures every section for Remix and reports its blocks invisible to
     * vanilla, so vanilla's section buffers are empty and Remix terrain carries no baked
     * light; the rebuild would only recapture identical geometry, publish an identical page,
     * and retire the old one. The gate is the capture gate SectionBuilderMixin latches, not
     * whether Remix is drawing the world: vanilla has no terrain geometry to relight whenever
     * capture is on, including with world rendering toggled off or the renderer
     * uninitialized. Toggling RTX off reloads the world renderer, which rebuilds every
     * section with vanilla light again.
     *
     * <p>While the client light storage publishes its live map ({@link LightStorageMixin})
     * the notifications never reach this hook: that mixin cancels {@code swapSectionMap} and
     * counts the pass's pending notifications itself. This hook covers the kill-switch path
     * and the passes that publish a real copy.
     */
    @Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipLightOnlyRebuild(LightLayer type, SectionPos pos, CallbackInfo ci) {
        if (RtxRemixMod.isRtxEnabled()) {
            SkippedSectionRebuilds.recordLightOnly();
            ci.cancel();
        }
    }

    /**
     * A section that becomes all air is a Remix removal candidate even if vanilla never
     * compiles it again. Since 26.2 vanilla's occlusion graph leaves sections in the client
     * cache's empty-section set out of the visible tree, so once the graph is rebuilt such
     * a section never reaches {@code setSectionMesh} and {@link BuiltChunkMixin} never sees
     * it go empty. The render thread validates the live section before removing anything.
     * A block edit reports the change here ({@code LevelChunk.setBlockState}).
     */
    @Inject(method = "onSectionEmptinessChanged", at = @At("HEAD"))
    private void rtxremix$sectionBecameEmpty(int sectionX, int sectionY, int sectionZ, boolean empty, CallbackInfo ci) {
        if (empty && RtxRemixMod.isRtxEnabled()) {
            SectionLeftRenderSetQueue.enqueueEmptyCandidate(sectionX, sectionY, sectionZ);
        }
    }

    // replaceWithPacketData runs only on the render thread, so these fields carry the
    // column's section emptiness from the head of an in-place reload to the point where
    // vanilla refreshes its empty-section set.
    private static boolean[] rtxremix$sectionWasNonEmpty = new boolean[0];
    private static boolean rtxremix$inPlaceReload;

    /**
     * An in-place reload refreshes the empty-section set without reporting each change
     * through {@code onSectionEmptinessChanged}; remember which sections held blocks.
     */
    @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
    private void rtxremix$recordSectionsBeforeReload(
            int chunkX,
            int chunkZ,
            ClientboundLevelChunkPacketData chunkData,
            CallbackInfoReturnable<LevelChunk> cir) {
        rtxremix$inPlaceReload = false;
        if (!RtxRemixMod.isRtxEnabled()) {
            return;
        }
        LevelChunk existing = ((ClientChunkCache) (Object) this).getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (existing == null) {
            return;
        }
        LevelChunkSection[] sections = existing.getSections();
        if (rtxremix$sectionWasNonEmpty.length < sections.length) {
            rtxremix$sectionWasNonEmpty = new boolean[sections.length];
        }
        for (int i = 0; i < sections.length; i++) {
            rtxremix$sectionWasNonEmpty[i] = !sections[i].hasOnlyAir();
        }
        rtxremix$inPlaceReload = true;
    }

    @Inject(
            method = "replaceWithPacketData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;refreshEmptySections(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void rtxremix$sectionsEmptiedByReload(
            int chunkX,
            int chunkZ,
            ClientboundLevelChunkPacketData chunkData,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (!rtxremix$inPlaceReload) {
            return;
        }
        rtxremix$inPlaceReload = false;
        LevelChunk chunk = ((ClientChunkCache) (Object) this).getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        int count = Math.min(sections.length, rtxremix$sectionWasNonEmpty.length);
        for (int i = 0; i < count; i++) {
            if (rtxremix$sectionWasNonEmpty[i] && sections[i].hasOnlyAir()) {
                SectionLeftRenderSetQueue.enqueueEmptyCandidate(chunkX, chunk.getSectionYFromSectionIndex(i), chunkZ);
            }
        }
    }

    // replaceWithPacketData runs only on the render thread, so one field carries the
    // start time from the head to the return of each apply.
    private static long rtxremix$applyStartNanos;

    /**
     * Times each chunk apply for {@link LocalChunkBatchPacing}, which sizes the
     * integrated server's batches from what a chunk costs this thread. Vanilla's early
     * return for a column outside the ring is cheap and sampled like any apply.
     */
    @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
    private void rtxremix$beginApplyTiming(
            int chunkX,
            int chunkZ,
            ClientboundLevelChunkPacketData chunkData,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (LocalChunkBatchPacing.ENABLED) {
            rtxremix$applyStartNanos = System.nanoTime();
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void rtxremix$endApplyTiming(
            int chunkX,
            int chunkZ,
            ClientboundLevelChunkPacketData chunkData,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (LocalChunkBatchPacing.ENABLED) {
            LocalChunkBatchPacing.recordApplyNanos(System.nanoTime() - rtxremix$applyStartNanos);
        }
    }

    // drop runs only on the render thread, so one pair of fields carries the start
    // time and whether the column was held from the head to the return of each unload.
    private static long rtxremix$unloadStartNanos;
    private static boolean rtxremix$unloadIsReal;

    /**
     * Times each unload of a column the client holds for {@link LocalChunkBatchPacing},
     * which paces the integrated server's unload packets from what one costs this
     * thread. An unload of a column the client does not hold (already replaced, or
     * outside its ring) does nothing and is not sampled.
     */
    @Inject(method = "drop", at = @At("HEAD"))
    private void rtxremix$beginUnloadTiming(ChunkPos pos, CallbackInfo ci) {
        if (!LocalChunkBatchPacing.UNLOAD_ENABLED) {
            return;
        }
        ClientChunkCache self = (ClientChunkCache) (Object) this;
        rtxremix$unloadIsReal = self.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null;
        rtxremix$unloadStartNanos = System.nanoTime();
    }

    @Inject(method = "drop", at = @At("RETURN"))
    private void rtxremix$endUnloadTiming(ChunkPos pos, CallbackInfo ci) {
        if (LocalChunkBatchPacing.UNLOAD_ENABLED && rtxremix$unloadIsReal) {
            LocalChunkBatchPacing.recordUnloadNanos(System.nanoTime() - rtxremix$unloadStartNanos);
        }
    }

    /**
     * Moving the view centre only moves the ring's window: the chunks it leaves behind stay
     * in their slots, and the unloads the server sends after the centre are ignored for them,
     * so nothing would report them until their slot is reused ({@link ClientChunkRing}). At
     * the head the cache still answers for the old centre, so the chunks still live there
     * are the ones to report.
     */
    @Inject(method = "updateViewCenter", at = @At("HEAD"))
    private void rtxremix$reportRingDepartures(int x, int z, CallbackInfo ci) {
        ClientChunkRing.reportDepartures((ClientChunkCache) (Object) this, x, z);
    }
}
