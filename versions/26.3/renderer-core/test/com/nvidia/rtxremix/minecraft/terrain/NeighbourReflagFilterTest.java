/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.COLUMN_ABSENT;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.COLUMN_LIT;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.COLUMN_UNLIT;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.buildIsAheadOfChunkLight;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.dropsLightDataReflag;
import static com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter.rebuildsRequested;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeighbourReflagFilterTest {
    /** Overworld height: sections -4..19. */
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final int MIN_SECTION_Y = -4;
    private static final int MAX_SECTION_Y = 19;
    private static final int TRACKER_RADIUS = 3;

    @Test
    void enabledDefaultsToTrueAndHonoursAnExplicitFalse() {
        assertTrue(NeighbourReflagFilter.enabledFrom(null));
        assertTrue(NeighbourReflagFilter.enabledFrom("true"));
        assertFalse(NeighbourReflagFilter.enabledFrom("false"));
    }

    @Test
    void aLightDataReflagIsDroppedOnlyWhileEnabledAndCapturing() {
        assertTrue(dropsLightDataReflag(true, true));
        assertFalse(dropsLightDataReflag(true, false));
        assertFalse(dropsLightDataReflag(false, true));
        assertFalse(dropsLightDataReflag(false, false));
    }

    @Test
    void countersDrainAndReset() {
        NeighbourReflagFilter.drainKept();
        NeighbourReflagFilter.drainBuildsAheadOfChunkLight();
        SkippedSectionRebuilds.drainLightDataReflags();

        NeighbourReflagFilter.recordKept(3);
        NeighbourReflagFilter.recordKept(0);
        NeighbourReflagFilter.recordBuildAheadOfChunkLight();
        SkippedSectionRebuilds.recordLightDataReflags(5);
        SkippedSectionRebuilds.recordLightDataReflags(0);

        assertEquals(3, NeighbourReflagFilter.drainKept());
        assertEquals(0, NeighbourReflagFilter.drainKept());
        assertEquals(1, NeighbourReflagFilter.drainBuildsAheadOfChunkLight());
        assertEquals(0, NeighbourReflagFilter.drainBuildsAheadOfChunkLight());
        assertEquals(5, SkippedSectionRebuilds.drainLightDataReflags());
        assertEquals(0, SkippedSectionRebuilds.drainLightDataReflags());
    }

    /**
     * Against vanilla's own tracker: the count is exactly the number of states that
     * {@code LevelExtractor.setSectionDirtyWithNeighbors} (vanilla's 3x3x3 loop over
     * {@code SectionUpdateTracker.setDirty(x, y, z, false)}) turns from clean to dirty,
     * including cubes that reach past the window and past the world's height.
     */
    @Test
    void rebuildsRequestedIsWhatVanillasReflagChangesInTheTracker() {
        int[][] centres = {
            {0, 5, 0},
            {TRACKER_RADIUS, 5, 0},
            {-TRACKER_RADIUS, 5, TRACKER_RADIUS},
            {TRACKER_RADIUS + 1, 5, 0},
            {1, MIN_SECTION_Y, -1},
            {1, MAX_SECTION_Y, 2},
            {0, MAX_SECTION_Y + 1, 0},
            {0, MIN_SECTION_Y - 1, 0},
            {TRACKER_RADIUS + 2, 5, 0},
        };
        Random random = new Random(26_3);
        for (int round = 0; round < 40; round++) {
            for (int[] centre : centres) {
                SectionUpdateTracker tracker = newTracker();
                // Random clean/dirty pattern, some of it dirty from the player.
                forEachWindowSection((x, y, z) -> {
                    SectionUpdateTracker.SectionDirtyState state = tracker.getDirtyState(SectionPos.asLong(x, y, z));
                    int roll = random.nextInt(4);
                    if (roll == 0) {
                        state.setNotDirty();
                        state.setDirty(true);
                    } else if (roll == 1) {
                        state.setNotDirty();
                        state.setDirty(false);
                    } else {
                        state.setNotDirty();
                    }
                });
                Map<Long, Boolean> dirtyBefore = new HashMap<>();
                Map<Long, Boolean> fromPlayerBefore = new HashMap<>();
                snapshot(tracker, dirtyBefore, fromPlayerBefore);

                int requested = rebuildsRequested(tracker, centre[0], centre[1], centre[2]);

                for (int z = centre[2] - 1; z <= centre[2] + 1; z++) {
                    for (int x = centre[0] - 1; x <= centre[0] + 1; x++) {
                        for (int y = centre[1] - 1; y <= centre[1] + 1; y++) {
                            tracker.setDirty(x, y, z, false);
                        }
                    }
                }
                Map<Long, Boolean> dirtyAfter = new HashMap<>();
                Map<Long, Boolean> fromPlayerAfter = new HashMap<>();
                snapshot(tracker, dirtyAfter, fromPlayerAfter);

                int flipped = 0;
                for (Map.Entry<Long, Boolean> entry : dirtyBefore.entrySet()) {
                    boolean before = entry.getValue();
                    boolean after = dirtyAfter.get(entry.getKey());
                    if (!before && after) {
                        flipped++;
                    }
                    if (before) {
                        // An already dirty state is left exactly as it was.
                        assertTrue(after);
                        assertEquals(fromPlayerBefore.get(entry.getKey()), fromPlayerAfter.get(entry.getKey()));
                    }
                }
                assertEquals(flipped, requested, "centre " + centre[0] + "," + centre[1] + "," + centre[2]);
            }
        }
    }

    @Test
    void rebuildsRequestedCountsTheWholeCleanCubeAndNothingOutsideTheWindow() {
        SectionUpdateTracker tracker = newTracker();
        forEachWindowSection((x, y, z) -> tracker.getDirtyState(SectionPos.asLong(x, y, z)).setNotDirty());
        assertEquals(27, rebuildsRequested(tracker, 0, 5, 0));
        // Half the cube past the window's x edge.
        assertEquals(18, rebuildsRequested(tracker, TRACKER_RADIUS, 5, 0));
        // The light sections just outside the world have no state, as vanilla's tracker has none.
        assertEquals(18, rebuildsRequested(tracker, 0, MAX_SECTION_Y, 0));
        assertEquals(9, rebuildsRequested(tracker, 0, MAX_SECTION_Y + 1, 0));
        assertEquals(0, rebuildsRequested(tracker, TRACKER_RADIUS + 2, 5, 0));
        // A fresh tracker starts every state dirty, so nothing would be requested.
        assertEquals(0, rebuildsRequested(newTracker(), 0, 5, 0));
    }

    @Test
    void aBuildIsAheadOfChunkLightOnlyWhenAHeldColumnOfItsNeighbourhoodIsUnlit() {
        Map<Long, Integer> states = new HashMap<>();
        NeighbourReflagFilter.ColumnProbe probe = (cx, cz) -> states.getOrDefault(columnKey(cx, cz), COLUMN_ABSENT);
        long node = SectionPos.asLong(10, 3, -7);

        assertFalse(buildIsAheadOfChunkLight(node, probe));
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                states.put(columnKey(10 + dx, -7 + dz), COLUMN_LIT);
            }
        }
        assertFalse(buildIsAheadOfChunkLight(node, probe));

        // Unlit columns just outside the 3x3 neighbourhood do not count.
        for (int d = -2; d <= 2; d++) {
            states.put(columnKey(10 + d, -9), COLUMN_UNLIT);
            states.put(columnKey(10 + d, -5), COLUMN_UNLIT);
            states.put(columnKey(8, -7 + d), COLUMN_UNLIT);
            states.put(columnKey(12, -7 + d), COLUMN_UNLIT);
        }
        assertFalse(buildIsAheadOfChunkLight(node, probe));

        // Any held but unlit column of the neighbourhood, the section's own included.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                long key = columnKey(10 + dx, -7 + dz);
                states.put(key, COLUMN_UNLIT);
                assertTrue(buildIsAheadOfChunkLight(node, probe), dx + "," + dz);
                states.put(key, COLUMN_ABSENT);
                assertFalse(buildIsAheadOfChunkLight(node, probe), dx + "," + dz);
                states.put(key, COLUMN_LIT);
            }
        }
    }

    /**
     * A join sent nearest first, as {@code PlayerChunkSender} sorts each batch, with every
     * column ready to send, however far the light queue falls behind: 26.3 builds every
     * section whose eight neighbour columns arrive exactly once, and no build is ahead of a
     * column's light. Every column has a strictly farther neighbour that arrives after it, so
     * its own light task runs before its last neighbour's. This is the redundancy the 1.21.1
     * snapshot-mask filter removed, gone in vanilla.
     */
    @Test
    void freshColumnsSentNearestFirstAreBuiltExactlyOnceUnderAnyLightQueueBacklog() {
        for (int seed = 0; seed < 60; seed++) {
            Random random = new Random(seed);
            Model model = new Model(14);
            int centreX = random.nextInt(model.width);
            int centreZ = random.nextInt(model.width);
            List<long[]> order = model.allColumns();
            Collections.shuffle(order, random);
            order.sort(java.util.Comparator.comparingLong(column -> {
                long dx = column[0] - centreX;
                long dz = column[1] - centreZ;
                return dx * dx + dz * dz;
            }));
            model.arriveInFrames(order, 1 + random.nextInt(80), random);
            model.settle();

            assertInteriorBuilt(model, 1, 1, "seed " + seed);
            assertEquals(0, model.redundantBuilds, "seed " + seed);
            assertEquals(0, model.buildsAhead, "seed " + seed);
        }
    }

    /**
     * In an arbitrary arrival order a column can arrive after all eight neighbours; while its
     * light waits behind a backlog its first build reads the same blocks as the rebuild its
     * own light task then requests. Every such repeat follows a build the probe counted.
     */
    @Test
    void freshColumnsInAnyOrderRepeatOnlyBuildsTheProbeCounts() {
        int scenariosWithRepeats = 0;
        for (int seed = 0; seed < 60; seed++) {
            Random random = new Random(seed);
            Model model = new Model(14);
            List<long[]> order = model.allColumns();
            Collections.shuffle(order, random);
            model.arriveInFrames(order, 1 + random.nextInt(80), random);
            model.settle();

            assertInteriorBuilt(model, 1, 2, "seed " + seed);
            assertTrue(model.redundantBuilds <= model.buildsAhead, "seed " + seed);
            assertEquals(model.redundantBuilds, model.redundantAfterAhead, "seed " + seed);
            assertEquals(model.totalBuilds - model.interiorCount(), model.redundantBuilds, "seed " + seed);
            if (model.redundantBuilds > 0) {
                scenariosWithRepeats++;
            }
        }
        assertTrue(scenariosWithRepeats > 0, "out-of-order arrivals under a backlog repeat some first builds");
    }

    private static void assertInteriorBuilt(Model model, int minBuilds, int maxBuilds, String context) {
        for (int x = 0; x < model.width; x++) {
            for (int z = 0; z < model.width; z++) {
                Column column = model.column(x, z);
                if (model.isInterior(x, z)) {
                    assertTrue(column.builds >= minBuilds && column.builds <= maxBuilds,
                            context + " column " + x + "," + z + " built " + column.builds);
                } else {
                    // An edge column lacks a neighbour column, so vanilla never admits it.
                    assertEquals(0, column.builds, context + " column " + x + "," + z);
                }
            }
        }
    }

    /**
     * The residual: columns return next to sections that kept their meshes, and the light
     * queue falls behind. A kept section re-flagged by one returning column is rebuilt
     * before the next one's light, then again from the same blocks. Every such repeat
     * follows a build the probe counted; when the light queue keeps up there is none.
     */
    @Test
    void returningColumnsRepeatOnlyBuildsTheProbeCounts() {
        int scenariosWithRepeats = 0;
        for (int seed = 0; seed < 60; seed++) {
            Random random = new Random(seed);
            Model model = new Model(14);
            model.arriveInFrames(model.allColumns(), 400, random);
            model.settle();
            model.resetCounters();

            List<long[]> band = new ArrayList<>();
            for (long[] column : model.allColumns()) {
                if (column[0] >= 7) {
                    band.add(column);
                }
            }
            for (long[] column : band) {
                model.drop((int) column[0], (int) column[1]);
            }
            model.settle();
            assertEquals(0, model.totalBuilds, "a drop flags nothing");

            Collections.shuffle(band, random);
            boolean backlog = random.nextBoolean();
            // Without a backlog at most 10 light tasks wait at the poll, which runs them all.
            model.arriveInFrames(band, backlog ? 20 + random.nextInt(60) : 1 + random.nextInt(5), random);
            model.settle();

            assertTrue(model.redundantBuilds <= model.buildsAhead, "seed " + seed);
            assertEquals(model.redundantBuilds, model.redundantAfterAhead, "seed " + seed);
            if (!backlog) {
                assertEquals(0, model.redundantBuilds, "seed " + seed);
                assertEquals(0, model.buildsAhead, "seed " + seed);
            } else if (model.redundantBuilds > 0) {
                scenariosWithRepeats++;
            }
        }
        assertTrue(scenariosWithRepeats > 0, "the residual exists under a light-queue backlog");
    }

    // ---- Helpers -----------------------------------------------------------------------

    private interface SectionVisitor {
        void visit(int x, int y, int z);
    }

    private static SectionUpdateTracker newTracker() {
        SectionUpdateTracker tracker = new SectionUpdateTracker(LevelHeightAccessor.create(MIN_Y, HEIGHT), TRACKER_RADIUS);
        tracker.repositionCamera(SectionPos.of(0, 5, 0));
        return tracker;
    }

    private static void forEachWindowSection(SectionVisitor visitor) {
        for (int x = -TRACKER_RADIUS; x <= TRACKER_RADIUS; x++) {
            for (int z = -TRACKER_RADIUS; z <= TRACKER_RADIUS; z++) {
                for (int y = MIN_SECTION_Y; y <= MAX_SECTION_Y; y++) {
                    visitor.visit(x, y, z);
                }
            }
        }
    }

    private static void snapshot(SectionUpdateTracker tracker, Map<Long, Boolean> dirty, Map<Long, Boolean> fromPlayer) {
        forEachWindowSection((x, y, z) -> {
            long node = SectionPos.asLong(x, y, z);
            SectionUpdateTracker.SectionDirtyState state = tracker.getDirtyState(node);
            dirty.put(node, state.isDirty());
            fromPlayer.put(node, state.isDirtyFromPlayer());
        });
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** One column with one section, as 26.3's chunk-load scheduling sees it. */
    private static final class Column {
        boolean held;
        boolean lit;
        /** Bumped on every arrival: the blocks a snapshot of this column reads. */
        int version;
        /** {@code SectionDirtyState}: a fresh state is dirty. */
        boolean dirty = true;
        /** {@code sectionMesh != UNCOMPILED}; an unload leaves the slot's mesh in place. */
        boolean compiled;
        int builds;
        int[] lastSnapshot;
        boolean lastBuildAhead;
    }

    /** A queued light task: a chunk's arrival or its light removal. */
    private record LightTask(int x, int z, boolean arrival) {
    }

    /**
     * A transcription of 26.3's chunk-load scheduling over a square of columns:
     * {@code ClientChunkCache.replaceWithPacketData} holds the column at once and queues its
     * light task ({@code ClientPacketListener.handleLevelChunkWithLight}); the task enables
     * the column's light and, if the column is still held, flags its 3x3 neighbourhood
     * ({@code applyLightData}, {@code enableChunkLight}); {@code ClientLevel.pollLightUpdates}
     * runs {@code max(10, n/10)} of {@code n} queued tasks per frame (all of them from 1000);
     * a drop releases the column and switches its light off at once ({@code ClientLevel.unload})
     * and queues the light removal; and {@code LevelExtractor.extract} builds a dirty section of
     * a held column if it has a mesh or all eight neighbour columns are held and lit.
     */
    private static final class Model implements NeighbourReflagFilter.ColumnProbe {
        final int width;
        final Column[] columns;
        final ArrayDeque<LightTask> lightQueue = new ArrayDeque<>();
        int totalBuilds;
        int redundantBuilds;
        int redundantAfterAhead;
        int buildsAhead;

        Model(int width) {
            this.width = width;
            this.columns = new Column[width * width];
            for (int i = 0; i < columns.length; i++) {
                columns[i] = new Column();
            }
        }

        List<long[]> allColumns() {
            List<long[]> all = new ArrayList<>();
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    all.add(new long[] {x, z});
                }
            }
            return all;
        }

        Column column(int x, int z) {
            return x < 0 || z < 0 || x >= width || z >= width ? null : columns[x * width + z];
        }

        boolean isInterior(int x, int z) {
            return x > 0 && z > 0 && x < width - 1 && z < width - 1;
        }

        int interiorCount() {
            return (width - 2) * (width - 2);
        }

        @Override
        public int rtxremix$columnState(int x, int z) {
            Column column = column(x, z);
            if (column == null || !column.held) {
                return COLUMN_ABSENT;
            }
            return column.lit ? COLUMN_LIT : COLUMN_UNLIT;
        }

        void resetCounters() {
            totalBuilds = 0;
            redundantBuilds = 0;
            redundantAfterAhead = 0;
            buildsAhead = 0;
        }

        void arrive(int x, int z) {
            Column column = column(x, z);
            column.held = true;
            column.version++;
            lightQueue.add(new LightTask(x, z, true));
        }

        void drop(int x, int z) {
            Column column = column(x, z);
            column.held = false;
            column.lit = false;
            lightQueue.add(new LightTask(x, z, false));
        }

        void arriveInFrames(List<long[]> order, int maxPerFrame, Random random) {
            int next = 0;
            while (next < order.size()) {
                int count = 1 + random.nextInt(maxPerFrame);
                for (int i = 0; i < count && next < order.size(); i++, next++) {
                    arrive((int) order.get(next)[0], (int) order.get(next)[1]);
                }
                frame();
            }
        }

        void settle() {
            int quietFrames = 0;
            for (int guard = 0; guard < 10_000 && quietFrames < 3; guard++) {
                int before = totalBuilds;
                boolean hadTasks = !lightQueue.isEmpty();
                frame();
                quietFrames = !hadTasks && totalBuilds == before ? quietFrames + 1 : 0;
            }
            assertTrue(lightQueue.isEmpty());
        }

        void frame() {
            int size = lightQueue.size();
            int perFrame = size < 1000 ? Math.max(10, size / 10) : size;
            for (int i = 0; i < perFrame && !lightQueue.isEmpty(); i++) {
                runLightTask(lightQueue.poll());
            }
            extract();
        }

        private void runLightTask(LightTask task) {
            Column column = column(task.x(), task.z());
            if (!task.arrival()) {
                column.lit = false;
                return;
            }
            column.lit = true;
            if (!column.held) {
                return;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Column neighbour = column(task.x() + dx, task.z() + dz);
                    if (neighbour != null) {
                        neighbour.dirty = true;
                    }
                }
            }
        }

        private void extract() {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    Column column = column(x, z);
                    if (!column.held || !column.dirty || !(column.compiled || hasAllNeighbours(x, z))) {
                        continue;
                    }
                    boolean ahead = buildIsAheadOfChunkLight(SectionPos.asLong(x, 0, z), this);
                    int[] snapshot = snapshot(x, z);
                    if (column.lastSnapshot != null && java.util.Arrays.equals(snapshot, column.lastSnapshot)) {
                        redundantBuilds++;
                        if (column.lastBuildAhead) {
                            redundantAfterAhead++;
                        }
                    }
                    if (ahead) {
                        buildsAhead++;
                    }
                    column.lastSnapshot = snapshot;
                    column.lastBuildAhead = ahead;
                    column.compiled = true;
                    column.dirty = false;
                    column.builds++;
                    totalBuilds++;
                }
            }
        }

        private boolean hasAllNeighbours(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && rtxremix$columnState(x + dx, z + dz) != COLUMN_LIT) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** The blocks a region snapshot reads: each column's version, or -1 when the column is not held. */
        private int[] snapshot(int x, int z) {
            int[] versions = new int[9];
            int i = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Column neighbour = column(x + dx, z + dz);
                    versions[i++] = neighbour != null && neighbour.held ? neighbour.version : -1;
                }
            }
            return versions;
        }
    }
}
