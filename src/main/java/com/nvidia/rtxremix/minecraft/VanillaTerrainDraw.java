/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

/**
 * The one decision on whether vanilla draws its terrain this frame, with the
 * per-frame tallies the frame profiler annotates its phases with.
 *
 * <p>While Remix renders the world, Remix owns the terrain geometry, so vanilla's
 * section draws ({@code ChunkSectionsToRender.renderGroup} and the OIT variant) are
 * cancelled at their head by {@code ChunkSectionsToRenderMixin}. Everything around
 * them (visibility walk, section compiles, uploads) keeps running, exactly as the
 * 1.21.1 {@code renderSectionLayer} cancellation left it. Render thread only; the
 * counters are reset at the start of every {@code LevelRenderer.render}.
 */
public final class VanillaTerrainDraw {

    private static int calls;
    private static int skips;

    private VanillaTerrainDraw() {
    }

    /** Records one vanilla terrain draw request and says whether to cancel it. */
    public static boolean skipVanillaTerrain() {
        calls++;
        if (RtxRemixMod.isRemixWorldRendering()) {
            skips++;
            return true;
        }
        return false;
    }

    public static void resetFrame() {
        calls = 0;
        skips = 0;
    }

    public static int calls() {
        return calls;
    }

    public static int skips() {
        return skips;
    }
}
