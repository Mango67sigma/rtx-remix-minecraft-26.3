/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The block entity behind a vanilla {@code BlockEntityRenderState}, attached by
 * {@code BlockEntityRenderStateMixin} and written by
 * {@code BlockEntityRenderDispatcherMixin} as the state is extracted.
 *
 * <p>The capture keys a block entity's retained instances by the block entity's
 * identity and reads the Copper Beacon's lens state from the block entity itself,
 * neither of which the render state carries.
 */
public interface RemixBlockEntityRenderStateAccess {

    /** The block entity this state was extracted from, or null for a state built by other means. */
    BlockEntity rtxremix$blockEntity();

    void rtxremix$setBlockEntity(BlockEntity blockEntity);
}
