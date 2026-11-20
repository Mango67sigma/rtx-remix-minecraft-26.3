/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkInfoPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;

/**
 * Builds vanilla's visibility state on the slot-indexed node map {@link VisibilityGraphBuffers}
 * keeps across walks instead of a fresh array sized to the whole storage, and hands the state
 * the node pool of the same buffer set. Vanilla's fields and readers are unchanged.
 *
 * <p>Minecraft 26.3's {@code GraphStorage(ViewArea)} also builds an {@code Octree} and the
 * {@code sectionsWaitingForChunkLoads} map; both stay vanilla (see {@link VisibilityGraphBuffers}).
 */
@Mixin(GraphStorage.class)
public abstract class RenderableChunksMixin implements RenderableChunksBuffers {

    private ChunkInfoPool rtxremix$infoPool;

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "(I)Lnet/minecraft/client/renderer/SectionOcclusionGraph$SectionToNodeMap;"))
    private SectionToNodeMap rtxremix$reusedInfoList(int chunkCount) {
        VisibilityGraphBuffers.Buffers buffers = VisibilityGraphBuffers.current();
        if (buffers.infos.nodes.length != chunkCount) {
            // The buffers were sized for another storage; never index past either.
            rtxremix$infoPool = new ChunkInfoPool(chunkCount);
            return new SectionToNodeMap(chunkCount);
        }
        rtxremix$infoPool = buffers.infoPool;
        return buffers.infos;
    }

    @Override
    public ChunkInfoPool rtxremix$infoPool() {
        return rtxremix$infoPool;
    }
}
