/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixBlockEntityRenderStateAccess;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the block entity a render state was extracted from; see {@link RemixBlockEntityRenderStateAccess}. */
@Mixin(BlockEntityRenderState.class)
public abstract class BlockEntityRenderStateMixin implements RemixBlockEntityRenderStateAccess {

    @Unique
    private BlockEntity rtxremix$blockEntity;

    @Override
    public BlockEntity rtxremix$blockEntity() {
        return rtxremix$blockEntity;
    }

    @Override
    public void rtxremix$setBlockEntity(BlockEntity blockEntity) {
        rtxremix$blockEntity = blockEntity;
    }
}
