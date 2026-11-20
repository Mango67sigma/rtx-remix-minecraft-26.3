/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixEntityRenderStateAccess;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the entity a render state was extracted from; see {@link RemixEntityRenderStateAccess}. */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements RemixEntityRenderStateAccess {

    @Unique
    private Entity rtxremix$entity;

    @Override
    public Entity rtxremix$entity() {
        return rtxremix$entity;
    }

    @Override
    public void rtxremix$setEntity(Entity entity) {
        rtxremix$entity = entity;
    }
}
