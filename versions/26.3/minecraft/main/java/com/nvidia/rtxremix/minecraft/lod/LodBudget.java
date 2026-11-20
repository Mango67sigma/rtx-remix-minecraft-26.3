/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import java.util.List;

/**
 * The cap on installed LOD mesh bytes, spent on the ground nearest the camera.
 *
 * <p>The LOD mod's range far outruns what video memory can hold as Remix meshes, which
 * take several times the mod's own bytes once Remix has built its buffers and acceleration
 * structures. A section that would overflow the cap gives way to nothing nearer than
 * itself: farther meshes are given up, farthest first, and if that is not enough the new
 * one is not installed, the section keeping any mesh it had. Either way the budget's radius
 * shrinks to the nearest section left out, and sections past it are not built again until
 * the radius grows back, each keeping any mesh it still has.
 *
 * <p>Render thread only. The shrinking radius and its growing back follow
 * {@code LodTerrain.makeRoom} and {@code revisitStarved} in
 * kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
final class LodBudget {

    /** Share of the cap kept free after giving meshes up, so the next arrival does not do it again. */
    private static final int HEADROOM_DIVISOR = 16;

    private final long limitBytes;
    private long installedBytes;
    private double radius = Double.POSITIVE_INFINITY;

    LodBudget(long limitBytes) {
        if (limitBytes <= 0) throw new IllegalArgumentException("LOD mesh budget must be positive");
        this.limitBytes = limitBytes;
    }

    long limitBytes() {
        return limitBytes;
    }

    long installedBytes() {
        return installedBytes;
    }

    /** Blocks from the camera past which nothing is built; infinite until the cap was reached. */
    double radius() {
        return radius;
    }

    /** Whether a section this far from the camera may be built. */
    boolean reaches(double distance) {
        return distance < radius;
    }

    /** Whether {@code bytes} more at {@code distance} fit without giving anything up. */
    boolean fits(double distance, long bytes) {
        return reaches(distance) && installedBytes + bytes <= limitBytes;
    }

    void installed(long bytes) {
        installedBytes += bytes;
    }

    void removed(long bytes) {
        installedBytes = Math.max(0, installedBytes - bytes);
    }

    /**
     * Decides whether a mesh of {@code bytes} at {@code distance} may be installed.
     *
     * @param installedFarFirst sections holding an installed mesh, farthest first, with
     *                          their {@code distance} and {@code meshBytes} current
     * @return how many of {@code installedFarFirst}, from the front, must give up their
     *         meshes first, 0 when it fits as it is, or -1 when it does not fit at all
     */
    int admit(double distance, long bytes, List<LodSection> installedFarFirst) {
        if (!reaches(distance)) return -1;
        if (installedBytes + bytes <= limitBytes) return 0;

        long target = limitBytes - limitBytes / HEADROOM_DIVISOR;
        long freed = 0;
        int farther = 0;
        for (int count = installedFarFirst.size(); farther < count; farther++) {
            LodSection section = installedFarFirst.get(farther);
            if (section.distance <= distance) break;
            freed += section.meshBytes;
            if (installedBytes - freed + bytes <= target) {
                radius = Math.min(radius, section.distance);
                return farther + 1;
            }
        }
        if (farther > 0 && installedBytes - freed + bytes <= limitBytes) {
            radius = Math.min(radius, installedFarFirst.get(farther - 1).distance);
            return farther;
        }
        // Even what lies farther would not make room: this one is the first left out.
        radius = Math.min(radius, distance);
        return -1;
    }

    /**
     * Lets the radius grow back once enough of the cap is free, so ground left out when the
     * camera stood elsewhere is reconsidered. Called when the camera has moved.
     *
     * @return whether the radius grew
     */
    boolean relax() {
        if (radius == Double.POSITIVE_INFINITY) return false;
        if (installedBytes <= limitBytes / 2) {
            radius = Double.POSITIVE_INFINITY;
            return true;
        }
        if (installedBytes <= limitBytes / 4 * 3) {
            radius *= 1.25;
            return true;
        }
        return false;
    }
}
