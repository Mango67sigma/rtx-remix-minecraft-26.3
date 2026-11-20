/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkStorageTorusTest {
    private static final int RADIUS = 2;
    private static final int SIZE_XZ = RADIUS * 2 + 1;
    private static final int SIZE_Y = 3;
    private static final int MIN_SECTION_Y = -4;

    @Test
    void planeUpdatesMatchVanillaAcrossMovementCases() {
        assertEquivalent("first re-centre from the constructor layout", null, 11, -14);
        assertEquivalent("first re-centre at the origin", null, 0, 0);
        assertEquivalent("no horizontal movement", new Center(-2, 3), -2, 3);
        assertEquivalent("positive one-section move", new Center(0, 0), 1, 0);
        assertEquivalent("negative one-section move", new Center(0, 0), -1, 0);
        assertEquivalent("diagonal move", new Center(0, 0), 1, -1);
        assertEquivalent("multi-section move", new Center(-2, 3), 3, -1);
        assertEquivalent("diameter teleport", new Center(0, 0), SIZE_XZ, 0);
        assertEquivalent("beyond-diameter teleport", new Center(0, 0), 7, -6);
        assertEquivalent("negative coordinates", new Center(-7, -4), -8, -2);
        assertEquivalent("far coordinates", new Center(1_874_999, -1_874_999), 1_875_000, -1_875_001);
    }

    @Test
    void targetSectionMatchesVanillaAcrossSignsAndWraps() {
        int[] centers = {-1_875_000, -17, -6, -5, -1, 0, 1, 4, 5, 6, 17, 1_875_000};
        for (int center : centers) {
            int lowest = center - RADIUS;
            for (int grid = 0; grid < SIZE_XZ; grid++) {
                assertEquals(
                        lowest + Math.floorMod(grid - lowest, SIZE_XZ),
                        ChunkStorageTorus.targetSection(lowest, grid, SIZE_XZ),
                        "center=" + center + ", grid=" + grid);
            }
        }
    }

    @Test
    void renderDistanceThirtyTwoTouchesOnlyEnteringPlanes() {
        int radius = 32;
        int size = radius * 2 + 1;
        int verticalSections = 24;
        FakeStorage storage = new FakeStorage(size, verticalSections, -4);
        ChunkStorageTorus.Workspace workspace = new ChunkStorageTorus.Workspace();
        vanillaReposition(storage, radius, size, verticalSections, -4, 0, 0);
        storage.clearMoves();

        int moved = ChunkStorageTorus.reposition(storage, workspace, radius, size, verticalSections, -4, 1, 0);
        assertEquals(1_560, moved);

        storage.clearMoves();
        moved = ChunkStorageTorus.reposition(storage, workspace, radius, size, verticalSections, -4, 2, 1);
        assertEquals(3_096, moved);

        storage.clearMoves();
        moved = ChunkStorageTorus.reposition(storage, workspace, radius, size, verticalSections, -4, 2, 1);
        assertEquals(0, moved);
        assertEquals(130, storage.sectionReads);
    }

    private static void assertEquivalent(String description, Center initialCenter, int centerX, int centerZ) {
        FakeStorage initial = new FakeStorage(SIZE_XZ, SIZE_Y, MIN_SECTION_Y);
        if (initialCenter != null) {
            vanillaReposition(initial, RADIUS, SIZE_XZ, SIZE_Y, MIN_SECTION_Y, initialCenter.x, initialCenter.z);
        }
        FakeStorage expected = initial.copy();
        FakeStorage actual = initial.copy();

        vanillaReposition(expected, RADIUS, SIZE_XZ, SIZE_Y, MIN_SECTION_Y, centerX, centerZ);
        int moved = ChunkStorageTorus.reposition(
                actual, new ChunkStorageTorus.Workspace(), RADIUS, SIZE_XZ, SIZE_Y, MIN_SECTION_Y, centerX, centerZ);

        assertEquals(expected.sections, actual.sections, description + " final sections");
        assertEquals(expected.moves, actual.moves, description + " setSectionNode calls");
        assertEquals(expected.moves.size(), moved, description + " moved count");
    }

    /** {@code RotatingSectionStorage.repositionCenter}'s loop, transcribed. */
    private static void vanillaReposition(
            FakeStorage storage, int radius, int sizeXZ, int sizeY, int minY, int centerX, int centerZ) {
        int lowestX = centerX - radius;
        int lowestZ = centerZ - radius;
        for (int gridX = 0; gridX < sizeXZ; gridX++) {
            int newSectionX = lowestX + Math.floorMod(gridX - lowestX, sizeXZ);
            for (int gridZ = 0; gridZ < sizeXZ; gridZ++) {
                int newSectionZ = lowestZ + Math.floorMod(gridZ - lowestZ, sizeXZ);
                for (int gridY = 0; gridY < sizeY; gridY++) {
                    int newSectionY = minY + gridY;
                    int index = ChunkStorageTorus.index(gridX, gridY, gridZ, sizeXZ, sizeY);
                    Section section = storage.sections.get(index);
                    if (section.x != newSectionX || section.y != newSectionY || section.z != newSectionZ) {
                        storage.rtxremix$setSection(index, newSectionX, newSectionY, newSectionZ);
                    }
                }
            }
        }
    }

    private record Center(int x, int z) {
    }

    private record Section(int x, int y, int z) {
    }

    private record Move(int index, Section section) {
    }

    private static final class FakeStorage implements ChunkStorageTorus.StorageAccess {
        private final List<Section> sections;
        private final List<Move> moves = new ArrayList<>();
        private int sectionReads;

        /** {@code RotatingSectionStorage}'s constructor layout: slot (x, y, z) holds section (x, minY + y, z). */
        private FakeStorage(int sizeXZ, int sizeY, int minY) {
            sections = new ArrayList<>(sizeXZ * sizeY * sizeXZ);
            for (int i = 0; i < sizeXZ * sizeY * sizeXZ; i++) {
                sections.add(null);
            }
            for (int x = 0; x < sizeXZ; x++) {
                for (int y = 0; y < sizeY; y++) {
                    for (int z = 0; z < sizeXZ; z++) {
                        sections.set(ChunkStorageTorus.index(x, y, z, sizeXZ, sizeY), new Section(x, minY + y, z));
                    }
                }
            }
        }

        private FakeStorage(List<Section> sections) {
            this.sections = new ArrayList<>(sections);
        }

        private FakeStorage copy() {
            return new FakeStorage(sections);
        }

        private void clearMoves() {
            moves.clear();
            sectionReads = 0;
        }

        @Override
        public int rtxremix$getSectionX(int slotIndex) {
            sectionReads++;
            return sections.get(slotIndex).x;
        }

        @Override
        public int rtxremix$getSectionZ(int slotIndex) {
            sectionReads++;
            return sections.get(slotIndex).z;
        }

        @Override
        public void rtxremix$setSection(int slotIndex, int sectionX, int sectionY, int sectionZ) {
            Section section = new Section(sectionX, sectionY, sectionZ);
            sections.set(slotIndex, section);
            moves.add(new Move(slotIndex, section));
        }
    }
}
