/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.joml.Quaternionf;

/**
 * One placed Copper Beacon's articulation for one frame, owned by its
 * {@link CopperBeaconRenderState} and the state the renderer's models are submitted
 * with.
 *
 * <p>It lives on the render state rather than on the renderer's shared model parts
 * because vanilla's collector compiles a model at feature prepare, after every block
 * entity of the frame has been submitted, and hands each model's {@code setupAnim}
 * the state it was submitted with then. Parts posed at submit time would show the
 * last submitted beacon's pose on every beacon.
 */
public final class CopperBeaconArticulation {
    /** Rotation from the model's floor-mounted, north-facing frame onto the placement. */
    public final Quaternionf mountingRotation = new Quaternionf();
    /** Yaw of the fork and the lens about the mount axis, in model radians. */
    public float modelYawRadians;
    /** Pitch of the head and the lens about the fork's bearings, in model radians. */
    public float modelPitchRadians;
}
