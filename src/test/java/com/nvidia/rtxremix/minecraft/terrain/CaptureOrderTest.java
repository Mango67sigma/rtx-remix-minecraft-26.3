/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The render thread's guard against captures that read older world state than what a section
 * already shows ({@code ChunkManager.drainBlockData}): a compile cancelled by a recompile keeps
 * running, so two captures of one section can arrive in either order, and a section that
 * turned to air is removed while an older compile of it may still be in flight.
 */
class CaptureOrderTest {

    private static final int CX = 2;
    private static final int SY = 5;
    private static final int CZ = -3;

    /** The drain's decision and bookkeeping for one arriving capture. */
    private static boolean apply(TerrainState terrain, int hash, long sequence, boolean liveEmpty) {
        TerrainState.SectionEntry current = terrain.findSection(CX, SY, CZ);
        if (ChunkManager.isSupersededCapture(liveEmpty, current, sequence)) {
            return false;
        }
        TerrainState.SectionEntry entry = terrain.addSection(CX, SY, CZ, hash, new Object());
        if (entry == null) {
            entry = terrain.findSection(CX, SY, CZ);
        }
        entry.noteCaptureSequence(sequence);
        return true;
    }

    private static int shownHash(TerrainState terrain) {
        return terrain.findSection(CX, SY, CZ).dataHash;
    }

    @Test
    void anOlderCaptureArrivingLastDoesNotReplaceTheNewerOne() {
        TerrainState terrain = new TerrainState(64);
        assertTrue(apply(terrain, 0xB, 7, false));
        assertFalse(apply(terrain, 0xA, 5, false), "the older snapshot must be ignored");
        assertEquals(0xB, shownHash(terrain));
        assertEquals(7, terrain.findSection(CX, SY, CZ).captureSequence());
    }

    @Test
    void capturesInSnapshotOrderApplyAsBefore() {
        TerrainState terrain = new TerrainState(64);
        assertTrue(apply(terrain, 0xA, 5, false));
        assertTrue(apply(terrain, 0xB, 7, false));
        assertEquals(0xB, shownHash(terrain));
    }

    @Test
    void anIdenticalNewerCaptureStillRaisesTheEntrysSequence() {
        TerrainState terrain = new TerrainState(64);
        assertTrue(apply(terrain, 0xA, 5, false));
        // Same geometry as shown: addSection keeps the entry, which now stands for snapshot 7.
        assertTrue(apply(terrain, 0xA, 7, false));
        assertEquals(7, terrain.findSection(CX, SY, CZ).captureSequence());
        // A capture taken between the two, of different blocks, is older than what is shown.
        assertFalse(apply(terrain, 0xC, 6, false));
        assertEquals(0xA, shownHash(terrain));
    }

    @Test
    void aCaptureOfASectionThatIsAirNowIsDropped() {
        TerrainState terrain = new TerrainState(64);
        assertFalse(apply(terrain, 0xA, 9, true), "removed geometry must not come back");
        assertNull(terrain.findSection(CX, SY, CZ));
    }

    @Test
    void unstampedCapturesAndEntriesAreNeverTreatedAsOlder() {
        TerrainState terrain = new TerrainState(64);
        TerrainState.SectionEntry unstamped = terrain.addSection(CX, SY, CZ, 0xA, new Object());
        assertEquals(0, unstamped.captureSequence());
        assertFalse(unstamped.supersedes(1));
        unstamped.noteCaptureSequence(4);
        assertFalse(unstamped.supersedes(0), "a capture without a sequence is not ordered");
        assertTrue(unstamped.supersedes(3));
        assertFalse(unstamped.supersedes(4), "the same snapshot is not older than itself");
        unstamped.noteCaptureSequence(2);
        assertEquals(4, unstamped.captureSequence(), "the sequence never moves back");
    }
}
