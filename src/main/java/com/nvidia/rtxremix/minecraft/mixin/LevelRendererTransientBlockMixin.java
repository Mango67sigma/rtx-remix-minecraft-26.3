/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixEntityPass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Captures the placeholder block a landed falling block leaves, where vanilla submits it,
 * instead of letting it fall into the OpenGL frame that Remix's image replaces.
 *
 * <p>{@code LevelRenderer.submitTransientBlocks} submits each live
 * {@code TransientBlockRenderState} as a moving block on a camera-relative stack for up to
 * a second after a {@code ClientboundAddTransientBlockPacket}. Vanilla's stack and collector
 * are deliberately not forwarded: the capture tesselates the block on its own identity stack
 * and roots the instance at the block position, as {@code LevelRendererBlockDamageMixin}
 * does for the crack. Vanilla's push, translate and pop around the call stay balanced
 * either way. When Remix declines, vanilla submits as usual.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTransientBlockMixin {

    @WrapOperation(
            method = "submitTransientBlocks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitMovingBlock(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/block/MovingBlockRenderState;I)V"
            ))
    private void rtxremix$captureTransientBlockInPlace(
            SubmitNodeCollector collector,
            PoseStack poseStack,
            MovingBlockRenderState state,
            int outlineColor,
            Operation<Void> original) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        RemixEntityPass pass = renderer == null ? null : renderer.getEntityPass();
        if (pass == null || !pass.captureTransientBlock(state)) {
            original.call(collector, poseStack, state, outlineColor);
        }
    }
}
