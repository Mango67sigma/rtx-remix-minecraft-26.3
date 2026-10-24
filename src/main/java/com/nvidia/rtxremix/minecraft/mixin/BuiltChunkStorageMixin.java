/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SkippedSectionRebuilds;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkStorageTorus;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-centres vanilla's render-section ring touching only the planes that move, and gives the
 * terrain pipeline section-addressed access to the slots.
 *
 * <p>Minecraft 26.3's {@code ViewArea} is backed by a {@code RotatingSectionStorage} addressed
 * in section coordinates; {@code repositionCamera(SectionPos)} compares every slot with its
 * target and reports whether the centre changed, invalidating the occlusion graph when it did.
 * {@link ChunkStorageTorus} reproduces that loop's result exactly while reading one slot per
 * grid column and row; the slot moves themselves go through vanilla's
 * {@code RenderSection.setSectionNode}, so its reset and the move log hooked there run as before.
 */
@Mixin(ViewArea.class)
public abstract class BuiltChunkStorageMixin
        implements ChunkStorageTorus.StorageAccess, BuiltChunkStorageSectionAccess {
    @Shadow
    @Final
    private SectionOcclusionGraph sectionOcclusionGraph;

    @Shadow
    @Final
    private RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections;

    /** The storage's sections by slot index, which is each section's {@code index}. */
    @Unique
    private SectionRenderDispatcher.RenderSection[] rtxremix$slots;

    @Unique
    private ChunkStorageTorus.Workspace rtxremix$torusWorkspace;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$indexSlots(CallbackInfo ci) {
        SectionRenderDispatcher.RenderSection[] slots = new SectionRenderDispatcher.RenderSection[sections.size()];
        // forEach, not the iterator: RotatingSectionStorage's iterator stops one short.
        sections.forEach(section -> slots[section.index] = section);
        rtxremix$slots = slots;
    }

    @Inject(method = "repositionCamera(Lnet/minecraft/core/SectionPos;)Z", at = @At("HEAD"), cancellable = true)
    private void rtxremix$repositionCamera(SectionPos cameraSectionPos, CallbackInfoReturnable<Boolean> cir) {
        if (!RtxRemixMod.isRtxEnabled() || rtxremix$slots == null) {
            return;
        }
        // RotatingSectionStorage.repositionCenter: an unchanged centre moves nothing and reports false.
        if (cameraSectionPos.equals(sections.centerSectionPos())) {
            cir.setReturnValue(false);
            return;
        }
        if (rtxremix$torusWorkspace == null) {
            rtxremix$torusWorkspace = new ChunkStorageTorus.Workspace();
        }

        int radius = sections.radius();
        int sizeXZ = radius * 2 + 1;
        try (ProfileScope scope = ProfileScope.begin("ChunkStorage.updateCameraPosition")) {
            int movedSlots = ChunkStorageTorus.reposition(
                    this,
                    rtxremix$torusWorkspace,
                    radius,
                    sizeXZ,
                    sections.height(),
                    sections.minY(),
                    cameraSectionPos.x(),
                    cameraSectionPos.z());
            scope.captureCount(movedSlots).scannedChunkCount(sizeXZ + sizeXZ);
        }
        ((RotatingSectionStorageAccessor) sections).rtxremix$setCenterSectionPos(cameraSectionPos);
        // ViewArea.repositionCamera: a changed centre invalidates the graph, even when only Y changed.
        sectionOcclusionGraph.invalidate();
        cir.setReturnValue(true);
    }

    @Override
    public int rtxremix$getSectionX(int slotIndex) {
        return SectionPos.x(rtxremix$slots[slotIndex].getSectionNode());
    }

    @Override
    public int rtxremix$getSectionZ(int slotIndex) {
        return SectionPos.z(rtxremix$slots[slotIndex].getSectionNode());
    }

    @Override
    public void rtxremix$setSection(int slotIndex, int sectionX, int sectionY, int sectionZ) {
        rtxremix$slots[slotIndex].setSectionNode(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    /** The slot holding the section, or null when the window does not hold it. */
    @Unique
    private SectionRenderDispatcher.RenderSection rtxremix$residentSlot(int cx, int sy, int cz) {
        SectionRenderDispatcher.RenderSection slot = sections.getValue(cx, sy, cz);
        return slot != null && slot.getSectionNode() == SectionPos.asLong(cx, sy, cz) ? slot : null;
    }

    @Override
    public void rtxremix$scheduleRebuildIfResident(SectionUpdateTracker tracker, int cx, int sy, int cz) {
        if (tracker != null) {
            tracker.setDirty(cx, sy, cz, false);
        }
    }

    @Override
    public void rtxremix$flagLoadedSection(SectionUpdateTracker tracker, int cx, int sy, int cz) {
        if (tracker == null || !NeighbourReflagFilter.sectionInRange(sy, sections.minY(), sections.height())) {
            return;
        }
        tracker.setDirty(cx, sy, cz, false);
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            SectionUpdateTracker.SectionDirtyState state =
                    tracker.getDirtyState(SectionPos.asLong(neighbourX, sy, neighbourZ));
            boolean resident = state != null;
            boolean awaitingBuild = resident && state.isDirty();
            // The snapshot bits live on the render section; a section the view area does not
            // hold yet has no snapshot, so nothing can be dropped for it.
            SectionRenderDispatcher.RenderSection slot = resident ? rtxremix$residentSlot(neighbourX, sy, neighbourZ) : null;
            int snapshotMask = slot != null ? ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$snapshotMask() : 0;
            int loaderBit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            switch (NeighbourReflagFilter.decideNeighbour(resident, awaitingBuild, snapshotMask, loaderBit)) {
                case FLAG -> {
                    state.setDirty(false);
                    NeighbourReflagFilter.recordKept();
                }
                case DROP_IN_SNAPSHOT -> SkippedSectionRebuilds.recordNeighbourInSnapshot();
                case SKIP_NOT_RESIDENT, SKIP_AWAITING_BUILD -> {
                }
            }
        }
    }

    @Override
    public void rtxremix$clearNeighbourSnapshotBits(int cx, int cz) {
        int bottomSection = sections.minY();
        int rows = sections.height();
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int bit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            for (int row = 0; row < rows; row++) {
                SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(neighbourX, bottomSection + row, neighbourZ);
                if (slot != null) {
                    ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$clearSnapshotBit(bit);
                }
            }
        }
    }
}
