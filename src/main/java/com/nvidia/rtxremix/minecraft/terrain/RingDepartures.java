/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Walks the chunk positions a client chunk ring stops covering when its centre moves.
 *
 * <p>The ring is the square window of slots {@code ClientChunkCache} keeps its chunks in: a
 * position is covered while it lies within the ring's radius of the centre on both axes
 * ({@link #covers}). Moving the centre uncovers every position the old window covers and the
 * new one does not. The walk visits each of them once, row by row in increasing {@code z}
 * and within a row in increasing {@code x}, and allocates nothing: a row the new window does
 * not reach departs whole, and any other row departs only on the side the centre moved away
 * from.
 */
public final class RingDepartures {

    private final int oldCenterX;
    private final int oldCenterZ;
    private final int newCenterX;
    private final int newCenterZ;
    private final int radius;
    private int x;
    private int z;
    private int runEndX;

    /** Starts a walk; {@link #next()} moves to the first position. */
    public RingDepartures(int oldCenterX, int oldCenterZ, int newCenterX, int newCenterZ, int radius) {
        this.oldCenterX = oldCenterX;
        this.oldCenterZ = oldCenterZ;
        this.newCenterX = newCenterX;
        this.newCenterZ = newCenterZ;
        this.radius = radius;
        this.z = oldCenterZ - radius - 1;
    }

    /** Whether a ring of {@code radius} around the centre covers the position, as the cache decides it. */
    public static boolean covers(int centerX, int centerZ, int radius, int x, int z) {
        return Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
    }

    /**
     * Moves to the next position the ring leaves.
     *
     * @return false once every such position has been visited
     */
    public boolean next() {
        if (x < runEndX) {
            x++;
            return true;
        }
        while (z < oldCenterZ + radius) {
            z++;
            if (startRun()) {
                return true;
            }
        }
        return false;
    }

    /** The current position's chunk x. */
    public int x() {
        return x;
    }

    /** The current position's chunk z. */
    public int z() {
        return z;
    }

    /**
     * Points the walk at the first departing position of row {@code z}. The two windows are
     * equally wide, so a row the new window reaches loses a run of at least one position on
     * the trailing side whenever the centre moved along x, and none when it did not.
     */
    private boolean startRun() {
        int oldMinX = oldCenterX - radius;
        int oldMaxX = oldCenterX + radius;
        if (Math.abs(z - newCenterZ) > radius) {
            x = oldMinX;
            runEndX = oldMaxX;
        } else if (newCenterX > oldCenterX) {
            x = oldMinX;
            runEndX = Math.min(oldMaxX, newCenterX - radius - 1);
        } else if (newCenterX < oldCenterX) {
            x = Math.max(oldMinX, newCenterX + radius + 1);
            runEndX = oldMaxX;
        } else {
            return false;
        }
        return true;
    }
}
