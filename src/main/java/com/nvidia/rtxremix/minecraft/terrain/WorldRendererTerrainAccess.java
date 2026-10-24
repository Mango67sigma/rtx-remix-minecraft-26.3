/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Terrain rebuild scheduling, implemented on {@code LevelRenderer} by
 * {@code LevelRendererTerrainMixin}. The section slots live in {@code LevelRenderer}'s view
 * area; the dirty state and the reload live in {@code LevelExtractor}
 * ({@link LevelExtractorTerrainAccess}), to which the scheduling calls are forwarded.
 *
 * <p>The recapture sequence is: take {@link #rtxremix$builtSectionPositions()}, clear Remix
 * terrain, call {@code LevelExtractor.allChanged()} (vanilla's reload; it builds the new view
 * area in the next extract), then {@link #rtxremix$queueSectionsForRemix(LongSet)}, which is
 * applied to that new view area.
 */
public interface WorldRendererTerrainAccess {

    /**
     * The sections vanilla currently holds a compiled mesh for, as packed
     * {@code SectionPos} longs. This is the terrain the player has seen: vanilla compiles a
     * section when its occlusion walk reaches it, so the set is the frustum-driven subset of
     * the loaded area. Take it before the reload's next extract, which replaces the view
     * area and forgets it.
     */
    LongSet rtxremix$builtSectionPositions();

    /**
     * Queues the loaded, non-empty, lit sections in {@code sections} for Remix capture,
     * nearest the camera first; after a pending reload, on the view area it creates.
     *
     * @return the positions accepted for queueing, as packed {@code SectionPos} longs;
     *         a subset of {@code sections}, never a view of it
     */
    LongSet rtxremix$queueSectionsForRemix(LongSet sections);

    /**
     * Flags a section for vanilla rebuild if the section tracker's window still holds it.
     * A section that has left the window is skipped: it is dirty again when it re-enters.
     */
    void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz);

    /**
     * Forgets that column (cx, cz) was in the region snapshots of its eight
     * neighbour columns' slots, because it unloaded or is being reloaded in
     * place. Its next load then re-flags those slots instead of being dropped
     * as already captured. A no-op while {@code NeighbourReflagFilter} is deferred.
     */
    void rtxremix$onNeighbourColumnInvalidated(int cx, int cz);
}
