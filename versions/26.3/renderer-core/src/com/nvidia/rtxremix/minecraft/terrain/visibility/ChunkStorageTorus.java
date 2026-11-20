/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

/**
 * Re-centres the horizontal ring of vanilla render-section slots as the camera moves, and
 * reports how many slots moved.
 *
 * <p>The wrap arithmetic must match {@code RotatingSectionStorage.repositionCenter} bit for
 * bit: a slot that lands on a different section from the one vanilla would have chosen puts
 * vanilla's visibility graph and the Remix terrain index on different sections. Vanilla
 * compares every slot's section with its target; this touches only the planes whose target
 * changed. That is exact because every slot of grid column {@code x} holds section X
 * {@code targetX(x)} and every slot of grid row {@code z} section Z {@code targetZ(z)} after
 * any re-centre (and in the constructor layout, where slot {@code (x, y, z)} holds section
 * {@code (x, minSectionY + y, z)}), while a slot's section Y never changes. Render thread
 * only, alongside the storage it mutates.
 */
public final class ChunkStorageTorus {

    private ChunkStorageTorus() {
    }

    /**
     * Moves every slot whose target section changed for a centre at section
     * ({@code centerSectionX}, {@code centerSectionZ}).
     *
     * @param sizeXZ      {@code radius * 2 + 1}, the grid's horizontal size
     * @param sizeY       number of section rows, {@code maxSectionY - minSectionY + 1}
     * @param minSectionY the lowest section Y the storage holds
     * @return the number of slots whose section changed
     */
    public static int reposition(
            StorageAccess storage,
            Workspace workspace,
            int radius,
            int sizeXZ,
            int sizeY,
            int minSectionY,
            int centerSectionX,
            int centerSectionZ) {
        workspace.resize(sizeXZ);
        int lowestX = centerSectionX - radius;
        int lowestZ = centerSectionZ - radius;

        for (int x = 0; x < sizeXZ; x++) {
            int targetX = targetSection(lowestX, x, sizeXZ);
            workspace.targetX[x] = targetX;
            workspace.changedX[x] = storage.rtxremix$getSectionX(index(x, 0, 0, sizeXZ, sizeY)) != targetX;
        }
        for (int z = 0; z < sizeXZ; z++) {
            int targetZ = targetSection(lowestZ, z, sizeXZ);
            workspace.targetZ[z] = targetZ;
            workspace.changedZ[z] = storage.rtxremix$getSectionZ(index(0, 0, z, sizeXZ, sizeY)) != targetZ;
        }

        int movedSlots = 0;
        // Vanilla's order: grid X, then grid Z, then grid Y.
        for (int x = 0; x < sizeXZ; x++) {
            for (int z = 0; z < sizeXZ; z++) {
                if (!workspace.changedX[x] && !workspace.changedZ[z]) {
                    continue;
                }
                int targetX = workspace.targetX[x];
                int targetZ = workspace.targetZ[z];
                for (int y = 0; y < sizeY; y++) {
                    storage.rtxremix$setSection(index(x, y, z, sizeXZ, sizeY), targetX, minSectionY + y, targetZ);
                    movedSlots++;
                }
            }
        }
        return movedSlots;
    }

    /** {@code RotatingSectionStorage.repositionCenter}'s target section for one grid coordinate. */
    static int targetSection(int lowest, int gridCoordinate, int size) {
        return lowest + Math.floorMod(gridCoordinate - lowest, size);
    }

    /** {@code RotatingSectionStorage.getSectionIndex}. */
    public static int index(int x, int y, int z, int sizeXZ, int sizeY) {
        return (z * sizeY + y) * sizeXZ + x;
    }

    public interface StorageAccess {
        int rtxremix$getSectionX(int slotIndex);

        int rtxremix$getSectionZ(int slotIndex);

        void rtxremix$setSection(int slotIndex, int sectionX, int sectionY, int sectionZ);
    }

    public static final class Workspace {
        private int[] targetX = new int[0];
        private int[] targetZ = new int[0];
        private boolean[] changedX = new boolean[0];
        private boolean[] changedZ = new boolean[0];

        private void resize(int sizeXZ) {
            if (targetX.length != sizeXZ) {
                targetX = new int[sizeXZ];
                changedX = new boolean[sizeXZ];
                targetZ = new int[sizeXZ];
                changedZ = new boolean[sizeXZ];
            }
        }
    }
}
