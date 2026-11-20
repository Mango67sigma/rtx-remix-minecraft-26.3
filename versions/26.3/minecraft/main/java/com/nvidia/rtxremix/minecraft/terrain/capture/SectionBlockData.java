/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.light.LightEntry;
import java.util.List;
import net.minecraft.core.SectionPos;

/**
 * Captured block-level mesh data for a single chunk section (16x16x16),
 * produced by {@link BlockQuadEmitter}.
 *
 * <p>Mesh data is pre-categorized and interleaved in HardcodedVertex
 * layout via {@link SectionMeshData}. No intermediate SoA copies: since the
 * 26.x port every model quad is staged straight from the Fabric emitter, so the
 * per-quad record the FRAPI harvest path once used is gone.
 */
public record SectionBlockData(
    SectionPos sectionPos,
    SectionMeshData meshData,
    List<LightEntry> lights,
    /**
     * 64-bit openness mask for the top face (y=15).  Each face is divided
     * into an 8x8 grid of 2x2-block sub-regions.  Bit {@code i} is set
     * when sub-region {@code i} contains at least one non-opaque block.
     * A mask of 0 means the face is fully sealed.
     */
    long topFaceMask,
    /** Openness mask for the bottom face (y=0). See {@link #topFaceMask}. */
    long bottomFaceMask,
    /** Openness mask for the east face (x=15). See {@link #topFaceMask}. */
    long eastFaceMask,
    /** Openness mask for the west face (x=0). See {@link #topFaceMask}. */
    long westFaceMask,
    /** Openness mask for the south face (z=15). See {@link #topFaceMask}. */
    long southFaceMask,
    /** Openness mask for the north face (z=0). See {@link #topFaceMask}. */
    long northFaceMask,
    /**
     * Compact 16x16x16 opacity volume.  64 longs, one bit per block.
     * Bit at index {@code (y*256 + z*16 + x)} is {@code 1} when the block
     * is an opaque full cube, {@code 0} otherwise.  Used for intra-section
     * flood fill when seeding the 3D connectivity BFS from the player's
     * position.
     */
    long[] opacityBitmap
) {
}
