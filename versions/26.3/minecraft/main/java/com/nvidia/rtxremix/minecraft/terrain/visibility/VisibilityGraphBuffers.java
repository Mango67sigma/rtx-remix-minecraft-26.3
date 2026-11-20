/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import java.util.Arrays;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;

/**
 * The buffers behind vanilla's visibility state, kept across walks instead of allocated per
 * walk: the per-slot node map and the {@link ChunkInfoPool}. Two sets of them alternate,
 * because at most two states exist at once: the published one the render thread reads and
 * extends incrementally, and the one a full walk is filling on the visibility thread. Vanilla
 * submits a full walk only from the render thread at the start of a frame and only when the
 * previous walk has published, so when a walk starts refilling a buffer set, the render thread
 * has already moved to the other.
 *
 * <p>Only buffers whose reuse cannot change vanilla's output are kept. The node map is an
 * array indexed by slot and the pool hands out nodes reset exactly as vanilla's constructor
 * sets them. Minecraft 26.3's {@code sectionsWaitingForChunkLoads} map is deliberately left to
 * vanilla: its key set is iterated ({@code SectionOcclusionGraph.expectedChunks}), and a
 * reused, already grown hash table would iterate in a different order and could reorder
 * propagation seeds. The {@code Octree} that replaced the reached-section set is not pooled
 * either; see the port notes.
 *
 * <p>{@link #rotate} is called when a full walk begins, before its state is constructed;
 * {@link #configure} when the storage changes size, which also drops the old buffers.
 */
public final class VisibilityGraphBuffers {
    /** One state's worth of buffers. */
    public static final class Buffers {
        public final SectionToNodeMap infos;
        public final ChunkInfoPool infoPool;

        Buffers(int chunkCount) {
            infos = new SectionToNodeMap(Math.max(1, chunkCount));
            infoPool = new ChunkInfoPool(chunkCount);
        }

        void reset() {
            Arrays.fill(infos.nodes, null);
            infoPool.beginWalk();
        }
    }

    private static Buffers[] sets = new Buffers[0];
    private static int chunkCount;
    private static int current;

    private VisibilityGraphBuffers() {
    }

    /** Sizes the buffers for a storage of {@code chunkCount} slots; the next state starts from scratch. */
    public static synchronized void configure(int newChunkCount) {
        chunkCount = newChunkCount;
        sets = new Buffers[] {new Buffers(newChunkCount), new Buffers(newChunkCount)};
        current = 0;
    }

    /** Switches to the other buffer set and clears it, for the state a full walk is about to build. */
    public static synchronized void rotate() {
        if (sets.length == 0) {
            configure(chunkCount);
        }
        current ^= 1;
        sets[current].reset();
    }

    /** The buffers the state being constructed should use. */
    public static synchronized Buffers current() {
        if (sets.length == 0) {
            configure(chunkCount);
        }
        return sets[current];
    }
}
