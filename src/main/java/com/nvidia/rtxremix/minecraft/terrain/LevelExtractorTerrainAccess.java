/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Terrain rebuild scheduling owned by {@code LevelExtractor}, which holds the section dirty
 * state since 26.2. Implemented by {@code LevelExtractorTerrainMixin}; reached through
 * {@link WorldRendererTerrainAccess}. Render thread only.
 */
public interface LevelExtractorTerrainAccess {

    /**
     * Queues the loaded, non-empty, lit sections in {@code sections} for Remix capture,
     * nearest the camera first. When a vanilla reload ({@code LevelExtractor.allChanged}) is
     * pending, the queue is applied to the view area that reload creates, right after
     * {@code LevelRenderer.invalidateCompiledGeometry} builds it in the next extract;
     * otherwise it is applied at once.
     *
     * @return the positions that passed the filters now, as packed {@code SectionPos} longs;
     *         a subset of {@code sections}, never a view of it
     */
    LongSet rtxremix$queueSectionsForRemix(LongSet sections);

    /**
     * Flags a section for vanilla rebuild if the section tracker's window holds it. A
     * section outside the window is skipped: it is dirty again when it re-enters.
     */
    void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz);
}
