/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

/**
 * What {@link CopperBeaconBlockEntityRenderer} extracts from a placed Copper Beacon
 * for one frame: the mount and facing of the block, the presented yaw and pitch of
 * the articulated head at the frame's smooth time, the articulation they evaluate to,
 * and the lens state the lamp is drawn with. The lens's emissive material is not part
 * of it: the submitter keys that by the block entity itself, which reaches it as the
 * capture's owner.
 */
public final class CopperBeaconRenderState extends BlockEntityRenderState {
    public Direction up = Direction.UP;
    public Direction forward = Direction.NORTH;
    public float yawDegrees;
    public float pitchDegrees;
    /** This beacon's pose: the state its yoke and lens models are submitted with. */
    public final CopperBeaconArticulation articulation = new CopperBeaconArticulation();
    public boolean active;
    public float intensityMultiplier;
    public int lensTint = 0xFFFFFFFF;
}
