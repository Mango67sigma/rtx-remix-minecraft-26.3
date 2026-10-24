/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;

/**
 * The stack and owner a vanilla {@code ItemStackRenderState} was resolved from,
 * attached by {@code ItemStackRenderStateMixin} and written by
 * {@code ItemModelResolverMixin} as the state is built.
 *
 * <p>An item's render state names only its baked quads. The capture needs the stack,
 * to classify a block item's terrain layer and to find the lit element a held torch
 * carries, and the owner, to ask whether that element is burning in its holder's
 * hand.
 */
public interface RemixItemStackRenderStateAccess {

    /** The stack this state was resolved from, or null before the first resolve. */
    ItemStack rtxremix$stack();

    /** The owner the stack was resolved for: a living holder, a dropped item, a display, or null. */
    ItemOwner rtxremix$owner();

    void rtxremix$setSource(ItemStack stack, ItemOwner owner);
}
