/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the display transform an item layer is posed with, as it is submitted.
 *
 * <p>A hand draws its item scaled down by the model's display transform. The held
 * flame and smoke are world-sized, so the capture divides that scale back out, and
 * the layer's submit is the only place the transform is in scope: by the time the
 * collector sees the quads it has been folded into the pose.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public abstract class ItemLayerRenderStateMixin {

    @Shadow
    private ItemTransform itemTransform;

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtxremix$recordDisplayScale(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                             int lightCoords, int overlayCoords, int outlineColor,
                                             CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setItemDisplayScale(itemTransform.scale().x());
        }
    }
}
