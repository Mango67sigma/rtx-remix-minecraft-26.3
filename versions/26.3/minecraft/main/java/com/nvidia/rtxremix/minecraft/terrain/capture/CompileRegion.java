/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/**
 * What the terrain capture tracks on a render region ({@code RenderSectionRegion}) besides
 * its snapshot references ({@link RegionSnapshotRelease}).
 *
 * <p><b>Order.</b> A region's section copies are taken on the render thread when the region
 * is created, so the order in which regions are created is the order in which their block
 * snapshots were taken. Each region is stamped then with a
 * {@link BlockDataQueue#nextCaptureSequence() capture sequence}; the capture built from it
 * carries that sequence, and the render thread ignores a capture older than the one a
 * section already shows.
 *
 * <p><b>Ownership.</b> A region is created for one compile task. The task claims it at
 * construction and releases it once it ran or was cancelled before running. A region no task
 * claimed, because vanilla dropped its section update before compiling it, is released by
 * {@link #rtxremix$releaseIfUnclaimed()} where vanilla drops it.
 */
public interface CompileRegion {

    /** The capture sequence stamped on this region at construction. */
    long rtxremix$captureSequence();

    /** Records that a compile task owns this region and will release it. Render thread only. */
    void rtxremix$claimForTask();

    /**
     * Releases the region if no compile task claimed it. Render thread only.
     *
     * @return whether the region was released by this call
     */
    boolean rtxremix$releaseIfUnclaimed();
}
