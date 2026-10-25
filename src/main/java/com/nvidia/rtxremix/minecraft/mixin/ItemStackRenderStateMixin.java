/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixItemStackRenderStateAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the stack and owner an item render state was resolved from, and opens the
 * capture's item window around the state's submit.
 *
 * <p>Minecraft routes ordinary opaque items through its translucent item layer, and
 * clear glass through its cutout one, so the render type alone cannot tell a pane of
 * stained glass from an apple, or clear glass from a torch. The {@code ItemStack}
 * can, and the item's submit is the last point at which it is in scope before the
 * geometry reaches capture. The owner is what lets the capture decide whether a held
 * torch is burning in its holder's hand.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements RemixItemStackRenderStateAccess {

    @Unique
    private ItemStack rtxremix$stack;
    @Unique
    private ItemOwner rtxremix$owner;

    @Override
    public ItemStack rtxremix$stack() {
        return rtxremix$stack;
    }

    @Override
    public ItemOwner rtxremix$owner() {
        return rtxremix$owner;
    }

    @Override
    public void rtxremix$setSource(ItemStack stack, ItemOwner owner) {
        rtxremix$stack = stack;
        rtxremix$owner = owner;
    }

    /**
     * Forgets the source with the layers. The states live as long as the renderers and
     * the game render state that own them, so a source kept past its layers would hold
     * the previous world's player and level alive after a disconnect.
     * {@code updateForTopItem} clears first and {@code ItemModelResolverMixin} records
     * the new source at its RETURN.
     */
    @Inject(method = "clear", at = @At("HEAD"))
    private void rtxremix$clearSource(CallbackInfo ci) {
        rtxremix$stack = null;
        rtxremix$owner = null;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtxremix$beginItem(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                    int lightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass() || rtxremix$stack == null) {
            return;
        }
        context.beginItem(rtxremix$stack, rtxremix$livingOwner(), rtxremix$blockLayer(rtxremix$stack));
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void rtxremix$endItem(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                  int lightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.endItem();
        }
    }

    /**
     * The living entity holding the item, or null for a dropped item, an item frame
     * or a display, which carry their item without a hand.
     */
    @Unique
    private LivingEntity rtxremix$livingOwner() {
        ItemOwner owner = rtxremix$owner;
        if (owner instanceof LivingEntity living) {
            return living;
        }
        return owner == null ? null : owner.asLivingEntity();
    }

    /**
     * Asks the question terrain asks of a placed block, through the same
     * classifier, so a held block is classified exactly as its placed
     * counterpart is.
     */
    @Unique
    private static int rtxremix$blockLayer(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(
                        BlockQuadEmitter.classifyBlockLayer(blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }
}
