/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import net.minecraft.client.SectionUpdateTracker;

/**
 * Section-addressed access to the render-section slots owned by {@code ViewArea}.
 *
 * <p>Since Minecraft 26.2 a section's rebuild flag no longer lives on its render section:
 * {@code LevelExtractor} keeps it in a {@link SectionUpdateTracker}, a second rotating storage
 * over the same window. The methods that flag rebuilds therefore take the extractor's tracker,
 * while the snapshot bits they consult stay on the render-section slot.
 */
public interface BuiltChunkStorageSectionAccess {

    /**
     * Flags a section for rebuild only if the window still holds it. In 26.3 this is what
     * vanilla's {@code LevelExtractor.setSectionDirty} already does: the tracker's
     * {@code RotatingSectionStorage.getValue} returns null outside the window instead of
     * aliasing onto a slot, so the 1.21.1 residency guard is now vanilla behaviour. Kept so
     * callers state the rule in one place. Render thread only.
     */
    void rtxremix$scheduleRebuildIfResident(SectionUpdateTracker tracker, int cx, int sy, int cz);

    /**
     * Applies one chunk-load or light-data re-flag request for a section, vanilla's
     * {@code LevelExtractor.setSectionDirtyWithNeighbors(III)}, under the Remix capture rule.
     * The section's own slot is flagged as vanilla flags it. Each of the eight horizontal
     * neighbour slots at the same section Y is flagged only if the window holds that section,
     * has no rebuild pending, and its last region snapshot did not contain the loading column.
     * Vertical neighbours are not visited because every section Y of a loading column receives
     * its own request. A section Y outside the storage is ignored. Render thread only.
     */
    void rtxremix$flagLoadedSection(SectionUpdateTracker tracker, int cx, int sy, int cz);

    /**
     * Clears the bit for column (cx, cz) in every resident slot of its eight neighbour
     * columns after the column unloaded or before it is reloaded in place, so the next
     * load of that column re-flags them. Render thread only.
     */
    void rtxremix$clearNeighbourSnapshotBits(int cx, int cz);
}
