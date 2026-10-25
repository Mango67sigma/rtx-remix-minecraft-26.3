/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.SectionPos;

/**
 * Drops vanilla's light-data neighbour re-flags while RTX capture is on, and measures the
 * one chunk-load rebuild that Minecraft 26.3 can still repeat.
 *
 * <p><b>Light-data re-flags.</b> A light update packet re-flags the 3x3x3 neighbourhood of
 * every section it carries light for ({@code ClientPacketListener.readSectionList} calls
 * {@code LevelExtractor.setSectionDirtyWithNeighbors}, with {@code scheduleRebuild} true only
 * on the light update packet's path). The server sends those packets for chunks on a
 * player's tracked border, which is the loading frontier and the outer ring of the view, so
 * the inward neighbours already built are rebuilt for light alone. A Remix capture reads no
 * client light: the section builder diverts every block and fluid from vanilla's
 * tesselators and stores no baked light. The rebuild would therefore recapture identical
 * geometry, publish an identical page and retire the old one, so the whole re-flag is
 * dropped. Since 26.3 a chunk load no longer passes through this method (it uses
 * {@code setSectionRangeDirty} and applies its own light data with {@code scheduleRebuild}
 * false), so a dropped re-flag can never be the one that admits a section after a column
 * arrives. Chunk loads, block updates and biome changes stay vanilla.
 *
 * <p><b>Chunk loads.</b> 1.21.1 rebuilt a column's neighbours a second time on almost every
 * load, because its first-build gate tested only the four cardinal neighbour columns, only
 * for being in the cache, and not at all within 24 blocks of the camera, while the load's
 * re-flag came later with the column's light. 26.3 gates every first build on all eight
 * neighbour columns being loaded and lit ({@code SectionUpdateTracker.hasAllNeighbors}), and a
 * column's light comes on in the same render-thread task that re-flags its neighbourhood, so
 * a first build no longer precedes a neighbour's re-flag. Two cases remain, both while the
 * light queue is behind (vanilla runs at most {@code max(10, n/10)} of {@code n} queued light
 * tasks per frame): a first build whose own column arrived after all eight neighbours and
 * still waits for its light, which the server's nearest-first send order produces only for a
 * column that became ready to send after its neighbours, such as one generated late; and a section
 * that already has a mesh, rebuilt for another reason while a column of its 3x3
 * neighbourhood sits in the cache unlit. When that column's task runs it re-flags the
 * section, which is built again from the same blocks. {@link #buildIsAheadOfChunkLight}
 * counts those builds so that Milestone B can decide from measurements whether they need a
 * filter.
 *
 * <p>The legacy members at the end of this class are 1.21.1's snapshot-mask rule. Nothing
 * records a snapshot mask in 26.3; they remain only because
 * {@code BuiltChunkStorageMixin.rtxremix$flagLoadedSection}, which nothing calls, still
 * refers to them.
 */
public final class NeighbourReflagFilter {
    public static final String PROPERTY = "rtxremix.chunk.neighbourReflagFilter";

    /** Read once, so a session is wholly in one mode. */
    public static final boolean ENABLED = enabledFrom(System.getProperty(PROPERTY));

    /** A column of a section's 3x3 neighbourhood that the client does not hold. */
    public static final int COLUMN_ABSENT = 0;
    /** A column the client holds whose light, and with it its chunk-load re-flag, is still queued. */
    public static final int COLUMN_UNLIT = 1;
    /** A column the client holds with its light on. */
    public static final int COLUMN_LIT = 2;

    private static final AtomicInteger KEPT = new AtomicInteger();
    private static final AtomicInteger BUILDS_AHEAD_OF_CHUNK_LIGHT = new AtomicInteger();

    /**
     * The client's view of one chunk column for {@link #buildIsAheadOfChunkLight}. The
     * terrain mixin on {@code LevelExtractor} implements it, so probing allocates nothing.
     */
    public interface ColumnProbe {
        /** {@link #COLUMN_ABSENT}, {@link #COLUMN_UNLIT} or {@link #COLUMN_LIT}. */
        int rtxremix$columnState(int chunkX, int chunkZ);
    }

    private NeighbourReflagFilter() {
    }

    static boolean enabledFrom(String property) {
        return property == null || Boolean.parseBoolean(property);
    }

    /** Whether a light-data re-flag is dropped instead of passed to vanilla. */
    public static boolean dropsLightDataReflag(boolean enabled, boolean capturing) {
        return enabled && capturing;
    }

    /**
     * The section rebuilds that {@code LevelExtractor.setSectionDirtyWithNeighbors(x, y, z)}
     * would request: the sections of its 3x3x3 cube that the tracker holds and that are
     * clean. Vanilla's {@code SectionDirtyState.setDirty(false)} leaves an already dirty
     * state exactly as it was, and {@code SectionUpdateTracker.setDirty} ignores a position
     * outside the tracker's window or height, so these are the only states the call changes.
     * Render thread only; allocation-free.
     */
    public static int rebuildsRequested(SectionUpdateTracker tracker, int sectionX, int sectionY, int sectionZ) {
        int requested = 0;
        for (int z = sectionZ - 1; z <= sectionZ + 1; z++) {
            for (int x = sectionX - 1; x <= sectionX + 1; x++) {
                for (int y = sectionY - 1; y <= sectionY + 1; y++) {
                    SectionUpdateTracker.SectionDirtyState state = tracker.getDirtyState(SectionPos.asLong(x, y, z));
                    if (state != null && !state.isDirty()) {
                        requested++;
                    }
                }
            }
        }
        return requested;
    }

    /**
     * Whether a build of the section about to take its region snapshot reads the blocks of
     * a column whose chunk-load re-flag is still queued. That column's light task will
     * re-flag the section ({@code ClientPacketListener.enableChunkLight} flags the column's
     * 3x3 neighbourhood over the full height), and the section will be built again from the
     * same blocks unless another column of the neighbourhood arrives or a block changes in
     * between. An absent column does not count: the snapshot lacks its blocks, so the rebuild
     * its load triggers is needed. For a first build only the section's own column can be
     * unlit, because vanilla admits it only once all eight neighbour columns are lit.
     * Not counted, because the column's light already reads as on: a column received again
     * in place, which keeps its light, and a column dropped and received again before the
     * light task of its earlier arrival has run.
     */
    public static boolean buildIsAheadOfChunkLight(long sectionNode, ColumnProbe probe) {
        int sectionX = SectionPos.x(sectionNode);
        int sectionZ = SectionPos.z(sectionNode);
        for (int z = sectionZ - 1; z <= sectionZ + 1; z++) {
            for (int x = sectionX - 1; x <= sectionX + 1; x++) {
                if (probe.rtxremix$columnState(x, z) == COLUMN_UNLIT) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Records rebuilds a light-data re-flag requested with the filter switched off. */
    public static void recordKept(int rebuilds) {
        if (rebuilds > 0) {
            KEPT.addAndGet(rebuilds);
        }
    }

    /** Returns and resets the kept count, for the per-frame terrain counters. */
    public static int drainKept() {
        return KEPT.getAndSet(0);
    }

    /** Records a build that a queued chunk-load re-flag will repeat. */
    public static void recordBuildAheadOfChunkLight() {
        BUILDS_AHEAD_OF_CHUNK_LIGHT.incrementAndGet();
    }

    /** Returns and resets the builds-ahead count, for the per-frame terrain counters. */
    public static int drainBuildsAheadOfChunkLight() {
        return BUILDS_AHEAD_OF_CHUNK_LIGHT.getAndSet(0);
    }

    /** One startup log line that makes every run self-describing. */
    public static String effectiveStateLine() {
        return ENABLED
                ? "Light-data neighbour re-flags: dropped while RTX capture is on (" + PROPERTY + "=true); "
                        + "chunk-load re-flags stay vanilla"
                : "Light-data neighbour re-flags: vanilla (" + PROPERTY + "=false)";
    }

    // ---- Legacy 1.21.1 snapshot-mask rule ------------------------------------------------
    // Nothing records a snapshot mask in 26.3, so every mask is 0. These members remain only
    // for BuiltChunkStorageMixin.rtxremix$flagLoadedSection and
    // rtxremix$clearNeighbourSnapshotBits, which nothing calls; remove them together.

    /** Horizontal neighbour offsets in chunk columns. Mask bit k stands for neighbour (DX[k], DZ[k]). */
    public static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    public static final int[] DZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    public static final int NEIGHBOUR_COUNT = 8;
    public static final int COMPLETE = 0xFF;

    /** What 1.21.1's chunk load did to one horizontal neighbour slot of the loading column. */
    public enum Decision {
        /** The slot's capture never saw this column: flag it as vanilla would. */
        FLAG,
        /** The slot holds a different section; vanilla's flag would rebuild an unrelated one. */
        SKIP_NOT_RESIDENT,
        /** A rebuild is already pending and will snapshot the world after this load. */
        SKIP_AWAITING_BUILD,
        /** The slot's last snapshot already contained this column, so a rebuild would read identical data. */
        DROP_IN_SNAPSHOT
    }

    /** Index of the neighbour at horizontal chunk offset (dx, dz), each in -1..1 and not both zero. */
    public static int neighbourIndex(int dx, int dz) {
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1 || (dx == 0 && dz == 0)) {
            throw new IllegalArgumentException("not a horizontal neighbour offset: " + dx + ", " + dz);
        }
        int linear = (dz + 1) * 3 + (dx + 1);
        return linear < 4 ? linear : linear - 1;
    }

    /** The index under which neighbour k sees the column at the centre. */
    public static int mirroredIndex(int k) {
        return neighbourIndex(-DX[k], -DZ[k]);
    }

    /** Mask bit for neighbour index k. */
    public static int bit(int k) {
        return 1 << k;
    }

    /** Whether a section Y has a row of its own in a storage of {@code sizeY} rows from {@code bottomSection}. */
    public static boolean sectionInRange(int sy, int bottomSection, int sizeY) {
        return sy >= bottomSection && sy < bottomSection + sizeY;
    }

    /** 1.21.1's neighbour rule for one slot when a column loaded. */
    public static Decision decideNeighbour(boolean resident, boolean needsRebuild, int snapshotMask, int loaderBit) {
        if (!resident) {
            return Decision.SKIP_NOT_RESIDENT;
        }
        if (needsRebuild) {
            return Decision.SKIP_AWAITING_BUILD;
        }
        if ((snapshotMask & loaderBit) != 0) {
            return Decision.DROP_IN_SNAPSHOT;
        }
        return Decision.FLAG;
    }

    /** Records one neighbour flag passed through by the legacy rule. */
    public static void recordKept() {
        recordKept(1);
    }
}
