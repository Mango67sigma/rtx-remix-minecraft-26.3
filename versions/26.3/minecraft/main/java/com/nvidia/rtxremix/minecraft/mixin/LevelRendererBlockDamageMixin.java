/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockDamagePass;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Captures the block-breaking crack where vanilla submits it, instead of letting it
 * fall into the OpenGL frame that Remix's image replaces.
 *
 * <p>Vanilla's matrix stack and collector are deliberately not forwarded: the stack
 * is camera-relative and the collector feeds the GL breaking overlay, while the
 * capture supplies an identity stack and its own crack projection so the geometry
 * stays block-local. The parts and the block's model offset come from the loop's
 * render state; the stage is the submit's progress. When Remix declines, vanilla
 * submits as usual.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererBlockDamageMixin {

    @WrapOperation(
            method = "submitBlockDestroyAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;IZ)V"
            ))
    private void rtxremix$captureBlockDamageInPlace(
            SubmitNodeCollector collector,
            PoseStack poseStack,
            List<BlockStateModelPart> parts,
            int progress,
            boolean isBlockTranslucent,
            Operation<Void> original,
            @Local BlockBreakingRenderState state) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        BlockPos pos = state.blockPos();
        Vec3 modelOffset = state.blockState().getOffset(pos);
        boolean captured = renderer != null
                && renderer.renderBlockDamageInPlace(pos, progress,
                        (captureMatrices, captureConsumer) -> RemixBlockDamagePass.renderBreakingParts(
                                parts, modelOffset, captureMatrices, captureConsumer));
        if (!captured) {
            original.call(collector, poseStack, parts, progress, isBlockTranslucent);
        }
    }
}
