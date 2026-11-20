/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * Vanilla's line-of-sight probe for sections far from the camera, without its allocations.
 *
 * <p>Vanilla's visibility walk ({@code SectionOcclusionGraph.runUpdates}) leaves a section more
 * than three sections from the camera through a face only if a ray from a corner of that
 * section toward the camera passes exclusively through sections that exist and are already in
 * the graph. Since Minecraft 26.2 it holds the ray in two JOML {@code Vector3d}s allocated per
 * probe and allocates a {@code BlockPos} per step. This reproduces the same arithmetic in the
 * same order on doubles, calling JOML's own {@code fma} and {@code invsqrt} so that every JOML
 * option resolves exactly as it does for vanilla, so the probed positions and the verdict are
 * bit-identical to vanilla's.
 */
public final class FarChunkProbe {

    /** Answers whether the section holding a block position exists and is in the graph. */
    @FunctionalInterface
    public interface GraphLookup {
        boolean contains(int blockX, int blockY, int blockZ);
    }

    /** Squared distance at which the ray is close enough to the camera to stop. */
    private static final double NEAR_CAMERA_DISTANCE_SQUARED = 3600.0;

    private FarChunkProbe() {}

    /**
     * Runs the probe from the section at {@code origin}, the section being left (not the
     * neighbour being entered), through a face on {@code axis}, toward the camera at
     * ({@code cameraX}, {@code cameraY}, {@code cameraZ}).
     *
     * @param cameraSectionCenterX camera section origin plus eight, per axis; picks the corner
     *                             of the section the ray starts from
     * @param step                 ray advance per iteration in blocks
     * @param worldMaxY            {@code ViewArea.maxY()}, the highest block (inclusive, 319 for
     *                             the overworld); a point above it or below {@code worldMinY}
     *                             leaves the world, which passes
     * @param worldMinY            {@code ViewArea.minY()}, the lowest block
     * @return true when every probed section is in the graph or the ray left the world
     */
    public static boolean reachesCamera(
            int originX, int originY, int originZ,
            Direction.Axis axis,
            int cameraSectionCenterX, int cameraSectionCenterY, int cameraSectionCenterZ,
            double cameraX, double cameraY, double cameraZ,
            double step,
            int worldMaxY, int worldMinY,
            GraphLookup graph) {
        boolean maxX = axis == Direction.Axis.X
                ? cameraSectionCenterX > originX
                : cameraSectionCenterX < originX;
        boolean maxY = axis == Direction.Axis.Y
                ? cameraSectionCenterY > originY
                : cameraSectionCenterY < originY;
        boolean maxZ = axis == Direction.Axis.Z
                ? cameraSectionCenterZ > originZ
                : cameraSectionCenterZ < originZ;
        // new Vector3d(originX + (maxX ? 16 : 0), ...): integer sums, then widened.
        double pointX = originX + (maxX ? 16 : 0);
        double pointY = originY + (maxY ? 16 : 0);
        double pointZ = originZ + (maxZ ? 16 : 0);

        // new Vector3d(cameraPos).sub(checkPos).normalize().mul(step)
        double stepX = cameraX - pointX;
        double stepY = cameraY - pointY;
        double stepZ = cameraZ - pointZ;
        double invLength = org.joml.Math.invsqrt(
                org.joml.Math.fma(stepX, stepX, org.joml.Math.fma(stepY, stepY, stepZ * stepZ)));
        stepX = stepX * invLength;
        stepY = stepY * invLength;
        stepZ = stepZ * invLength;
        stepX = stepX * step;
        stepY = stepY * step;
        stepZ = stepZ * step;

        while (true) {
            // checkPos.distanceSquared(cameraPos.x, cameraPos.y, cameraPos.z)
            double dx = pointX - cameraX;
            double dy = pointY - cameraY;
            double dz = pointZ - cameraZ;
            if (!(org.joml.Math.fma(dx, dx, org.joml.Math.fma(dy, dy, dz * dz)) > NEAR_CAMERA_DISTANCE_SQUARED)) {
                return true;
            }
            pointX = pointX + stepX;
            pointY = pointY + stepY;
            pointZ = pointZ + stepZ;
            if (pointY > (double) worldMaxY || pointY < (double) worldMinY) {
                return true;
            }
            // BlockPos.containing(checkPos.x, checkPos.y, checkPos.z)
            if (!graph.contains(Mth.floor(pointX), Mth.floor(pointY), Mth.floor(pointZ))) {
                return false;
            }
        }
    }
}
