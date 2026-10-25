/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingDeparturesTest {

    private record Pos(int x, int z) {}

    private static List<Pos> walk(int oldX, int oldZ, int newX, int newZ, int radius) {
        RingDepartures departures = new RingDepartures(oldX, oldZ, newX, newZ, radius);
        List<Pos> visited = new ArrayList<>();
        while (departures.next()) {
            visited.add(new Pos(departures.x(), departures.z()));
        }
        assertFalse(departures.next(), "an exhausted walk stays exhausted");
        return visited;
    }

    /** Every position the old window covers and the new one does not, in increasing z, then x. */
    private static List<Pos> bruteForce(int oldX, int oldZ, int newX, int newZ, int radius) {
        List<Pos> expected = new ArrayList<>();
        for (int z = oldZ - radius; z <= oldZ + radius; z++) {
            for (int x = oldX - radius; x <= oldX + radius; x++) {
                if (!RingDepartures.covers(newX, newZ, radius, x, z)) {
                    expected.add(new Pos(x, z));
                }
            }
        }
        return expected;
    }

    @Test
    void coversTheSquareWithinTheRadiusOnBothAxes() {
        assertTrue(RingDepartures.covers(10, -4, 5, 15, -9));
        assertTrue(RingDepartures.covers(10, -4, 5, 5, 1));
        assertFalse(RingDepartures.covers(10, -4, 5, 16, -4));
        assertFalse(RingDepartures.covers(10, -4, 5, 10, 2));
        assertFalse(RingDepartures.covers(10, -4, 5, 4, -10));
    }

    @Test
    void oneChunkStepLeavesTheTrailingColumn() {
        List<Pos> expected = new ArrayList<>();
        for (int z = -5; z <= 5; z++) {
            expected.add(new Pos(-5, z));
        }
        assertEquals(expected, walk(0, 0, 1, 0, 5));
    }

    @Test
    void oneChunkStepTowardsNegativeZLeavesTheTrailingRow() {
        List<Pos> expected = new ArrayList<>();
        for (int x = 2; x <= 12; x++) {
            expected.add(new Pos(x, 2));
        }
        assertEquals(expected, walk(7, -3, 7, -4, 5));
    }

    @Test
    void diagonalStepLeavesTheTrailingColumnAndRowOnce() {
        int radius = 5;
        List<Pos> visited = walk(3, -2, 4, -1, radius);

        assertEquals(4 * radius + 1, visited.size());
        assertEquals(visited.size(), new HashSet<>(visited).size());
        for (Pos pos : visited) {
            assertTrue(pos.x() == 3 - radius || pos.z() == -2 - radius, "departed " + pos);
        }
        assertEquals(bruteForce(3, -2, 4, -1, radius), visited);
    }

    @Test
    void jumpBeyondTheDiameterLeavesTheWholeOldWindow() {
        int radius = 5;
        int side = 2 * radius + 1;
        List<Pos> wholeWindow = new ArrayList<>();
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                wholeWindow.add(new Pos(x, z));
            }
        }

        assertEquals(wholeWindow, walk(0, 0, side, 0, radius));
        assertEquals(wholeWindow, walk(0, 0, 0, -side, radius));
        assertEquals(wholeWindow, walk(0, 0, 1_000, -2_000, radius));
    }

    @Test
    void noMovementLeavesNothing() {
        assertEquals(List.of(), walk(7, -3, 7, -3, 35));
        assertEquals(List.of(), walk(0, 0, 0, 0, 0));
    }

    @Test
    void everyMoveVisitsExactlyTheUncoveredPositionsInRowOrder() {
        for (int radius = 0; radius <= 6; radius++) {
            int reach = 2 * radius + 3;
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    List<Pos> visited = walk(-40, 25, -40 + dx, 25 + dz, radius);
                    assertEquals(bruteForce(-40, 25, -40 + dx, 25 + dz, radius), visited,
                            "radius " + radius + ", move (" + dx + ", " + dz + ")");
                    int side = 2 * radius + 1;
                    int overlap = Math.max(0, side - Math.abs(dx)) * Math.max(0, side - Math.abs(dz));
                    assertEquals(side * side - overlap, visited.size());
                }
            }
        }
    }

    @Test
    void visitsEachDepartingPositionOnce() {
        Set<Pos> seen = new HashSet<>();
        for (Pos pos : walk(0, 0, 3, -2, 35)) {
            assertTrue(seen.add(pos), "visited twice: " + pos);
            assertTrue(RingDepartures.covers(0, 0, 35, pos.x(), pos.z()));
            assertFalse(RingDepartures.covers(3, -2, 35, pos.x(), pos.z()));
        }
        assertEquals(71 * 71 - 68 * 69, seen.size());
    }
}
