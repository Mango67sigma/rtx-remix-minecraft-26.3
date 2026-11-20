/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkStorageTorus;
import net.minecraft.client.RotatingSectionStorage;
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
 * Re-centres vanilla's render-section ring touching only the planes that move.
 *
 * <p>Minecraft 26.3's {@code ViewArea} is backed by a {@code RotatingSectionStorage} addressed
 * in section coordinates; {@code repositionCamera(SectionPos)} compares every slot with its
 * target and reports whether the centre changed, invalidating the occlusion graph when it did.
 * {@link ChunkStorageTorus} reproduces that loop's result exactly while reading one slot per
 * grid column and row; the slot moves themselves go through vanilla's
 * {@code RenderSection.setSectionNode}, so its reset and the move log hooked there run as before.
 */
@Mixin(ViewArea.class)
public abstract class BuiltChunkStorageMixin implements ChunkStorageTorus.StorageAccess {
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
}
