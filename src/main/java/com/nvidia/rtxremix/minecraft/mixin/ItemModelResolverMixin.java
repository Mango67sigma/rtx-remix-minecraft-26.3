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
 *
 * <p>The source is recorded at RETURN: {@code updateForTopItem} starts with
 * {@code output.clear()}, which {@code ItemStackRenderStateMixin} makes forget the
 * previous source, so a state only names a stack and owner while it holds that
 * stack's layers. An empty stack resolves to no layers and names nothing. Every
 * clear of a state, whether vanilla's own or the one that opens a re-resolve, drops
 * the reference to the holder and through it the client level.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {

    @Inject(method = "updateForTopItem", at = @At("RETURN"))
    private void rtxremix$recordSource(ItemStackRenderState output, ItemStack item,
                                       ItemDisplayContext displayContext, Level level,
                                       ItemOwner owner, int seed, CallbackInfo ci) {
        if (!item.isEmpty() && output instanceof RemixItemStackRenderStateAccess access) {
            access.rtxremix$setSource(item, owner);
        }
    }
}
