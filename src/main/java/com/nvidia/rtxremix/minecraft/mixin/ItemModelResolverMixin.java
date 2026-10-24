/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixItemStackRenderStateAccess;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Names the stack and owner on the render state an item is resolved into.
 *
 * <p>Every item that is drawn, in a hand, on the ground, in a frame or on a display,
 * passes through {@code updateForTopItem} first, which is the one place the stack,
 * its owner and the state meet; see {@link RemixItemStackRenderStateAccess}.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {

    @Inject(method = "updateForTopItem", at = @At("HEAD"))
    private void rtxremix$recordSource(ItemStackRenderState output, ItemStack item,
                                       ItemDisplayContext displayContext, Level level,
                                       ItemOwner owner, int seed, CallbackInfo ci) {
        if (output instanceof RemixItemStackRenderStateAccess access) {
            access.rtxremix$setSource(item, owner);
        }
    }
}
