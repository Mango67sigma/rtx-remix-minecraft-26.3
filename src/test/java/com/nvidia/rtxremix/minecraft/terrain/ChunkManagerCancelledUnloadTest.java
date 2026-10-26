/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A cancelled unload keeps the column, so the sections it shows that the live chunk has as
 * all air become removal candidates: vanilla never compiles an all-air section of a re-sent
 * chunk, and nothing else would retire what the dropped copy showed there.
 */
class ChunkManagerCancelledUnloadTest {

    private static final int CX = 5;
    private static final int CZ = -7;

    @BeforeEach
    void clearBeforeTest() {
        SectionLeftRenderSetQueue.clear();
    }

    @AfterEach
    void clearAfterTest() {
        SectionLeftRenderSetQueue.clear();
    }

    private static TerrainState.ChunkColumn columnWithSections(int... sectionYs) {
        TerrainState terrain = new TerrainState(TerrainState.MIN_LIGHT_CULL_BLOCKS);
        for (int sy : sectionYs) {
            terrain.addSection(CX, sy, CZ, 100 + sy, new Object());
        }
        return terrain.findColumn(CX, CZ);
    }

    private static Set<SectionLeftRenderSetQueue.SectionCoord> drainCandidates() {
        Set<SectionLeftRenderSetQueue.SectionCoord> candidates = new HashSet<>();
        SectionLeftRenderSetQueue.SectionCoord candidate;
        while ((candidate = SectionLeftRenderSetQueue.pollEmptyCandidate()) != null) {
            candidates.add(candidate);
        }
        return candidates;
    }

    @Test
    void queuesTheHeldSectionsTheLiveChunkHasAsAir() {
        TerrainState.ChunkColumn column = columnWithSections(-2, 0, 1, 3, 5);
        Set<Integer> liveAir = Set.of(-2, 1, 5, 9);

        ChunkManager.queueEmptiedSections(column, liveAir::contains);

        assertEquals(Set.of(
                        new SectionLeftRenderSetQueue.SectionCoord(CX, -2, CZ),
                        new SectionLeftRenderSetQueue.SectionCoord(CX, 1, CZ),
                        new SectionLeftRenderSetQueue.SectionCoord(CX, 5, CZ)),
                drainCandidates());
    }

    @Test
    void queuesNothingWhileEveryHeldSectionStillHasBlocks() {
        TerrainState.ChunkColumn column = columnWithSections(0, 1, 2);

        ChunkManager.queueEmptiedSections(column, sy -> sy > 2);

        assertNull(SectionLeftRenderSetQueue.pollEmptyCandidate());
    }
}
