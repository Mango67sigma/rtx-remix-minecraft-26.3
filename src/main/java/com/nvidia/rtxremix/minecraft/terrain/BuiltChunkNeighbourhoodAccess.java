/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Legacy per-slot record of which horizontal neighbour columns the slot's last region
 * snapshot contained (1.21.1's {@code NeighbourReflagFilter} rule). Minecraft 26.3 records no
 * snapshot mask: its first-build gate already waits for all eight neighbour columns, so the
 * mask is always 0. The interface remains only for
 * {@code BuiltChunkStorageMixin.rtxremix$flagLoadedSection} and
 * {@code rtxremix$clearNeighbourSnapshotBits}, which nothing calls; remove it with them.
 */
public interface BuiltChunkNeighbourhoodAccess {

    /** Always 0 in 26.3. */
    int rtxremix$snapshotMask();

    /** No-op in 26.3. */
    void rtxremix$clearSnapshotBit(int bit);
}
