/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.ChunkBuilderBlockEntityAccess;
import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkSlotMoveLog;
import net.minecraft.client.renderer.chunk.SectionMesh;
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
 * Hooks into vanilla's render-section lifecycle.
 *
 * <ul>
 *   <li><b>setSectionNode:</b> vanilla's {@code RotatingSectionStorage} hands a slot a new
 *       section this way (and the constructor gives it its first). The slot's move is
 *       logged at the return so the frustum index can re-bucket exactly the slots that
 *       moved.
 *   <li><b>reset:</b> runs at the head of every {@code setSectionNode} and from
 *       {@code ViewArea.releaseAllBuffers}; forgets the per-slot block-entity state.
 *   <li><b>setSectionMesh:</b> the one place a compiled mesh is published, the 26.3
 *       successor of {@code setCompiled}. Tracks whether the section contains block
 *       entities and flags a mesh without renderable layers as a removal candidate. The
 *       render thread validates the live world section before removing Remix terrain,
 *       because with capture on every section publishes a mesh without layers.
 * </ul>
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkMixin implements ChunkBuilderBlockEntityAccess {

    @Unique
    private static final long rtxremix$UNPLACED_NODE = SectionPos.asLong(-1, -1, -1);

    @Shadow
    @Final
    public int index;

    @Shadow
    public abstract long getSectionNode();

    private boolean rtxremix$hasBlockEntities;

    @Override
    public boolean rtxremix$hasBlockEntities() {
        return rtxremix$hasBlockEntities;
    }

    @Inject(method = "reset()V", at = @At("HEAD"))
    private void rtxremix$onReset(CallbackInfo ci) {
        rtxremix$hasBlockEntities = false;
    }

    @Inject(method = "setSectionNode(J)V", at = @At("RETURN"))
    private void rtxremix$afterSetSectionNode(long sectionNode, CallbackInfo ci) {
        ChunkSlotMoveLog.RENDER_THREAD.record(index);
    }

    /**
     * Runs on the compiling worker for a mesh without layers (the path every capturing
     * build takes) and on the render thread for an uploaded mesh, as {@code setCompiled}
     * did; the queue is concurrent.
     */
    @Inject(
            method = "setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;",
            at = @At("HEAD"))
    private void rtxremix$onSectionMeshSet(SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir) {
        rtxremix$hasBlockEntities = !sectionMesh.getRenderableBlockEntities().isEmpty();

        if (sectionMesh.hasRenderableLayers()) {
            return;
        }
        long node = getSectionNode();
        if (node == rtxremix$UNPLACED_NODE) {
            return;
        }
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
    }
}
