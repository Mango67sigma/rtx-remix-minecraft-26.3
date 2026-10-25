/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Legacy per-slot record of which horizontal neighbour columns the slot's last region
 * snapshot contained (1.21.1's {@code NeighbourReflagFilter} rule). Minecraft 26.3 records no
 * snapshot mask: its first-build gate already waits for all eight neighbour columns, so the
 * mask is always 0, and nothing reads it: the slot-flagging methods that consulted it were
 * removed from {@code BuiltChunkStorageMixin}. The interface remains only because
 * {@code BuiltChunkMixin} still implements it; remove both together.
 */
public interface BuiltChunkNeighbourhoodAccess {

    /** Always 0 in 26.3. */
    int rtxremix$snapshotMask();

    /** No-op in 26.3. */
    void rtxremix$clearSnapshotBit(int bit);
}
