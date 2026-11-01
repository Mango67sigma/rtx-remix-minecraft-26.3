/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockEntityRenderStateAccess;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures block entities where vanilla submits them, instead of rendering them twice.
 *
 * <p>Vanilla runs the block-entity renderer's {@code submit} here, into the frame's
 * collector, and the feature renderers write it to the GPU later. Each submit is offered
 * to {@code RemixRenderer.renderBlockEntityInPlace}, which runs the same renderer against
 * the Remix capture stack and collector and submits the result; the vanilla submit never
 * happens. Vanilla's own matrix stack and collector are deliberately not forwarded: the
 * stack is camera-relative, and the capture supplies an identity one so the geometry
 * stays block-local. When Remix declines, vanilla submits as usual.
 *
 * <p>The render state forgets the block entity it came from, and the capture keys the
 * block entity's retained instances and the Copper Beacon's lens by it, so
 * {@code tryExtractRenderState} attaches the block entity to the state it returns.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
    private <E extends BlockEntity> void rtxremix$attachBlockEntity(
            E blockEntity, float partialTicks,
            ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean isGloballyRendered,
            CallbackInfoReturnable<BlockEntityRenderState> cir) {
        if (cir.getReturnValue() instanceof RemixBlockEntityRenderStateAccess access) {
            access.rtxremix$setBlockEntity(blockEntity);
        }
    }

    @WrapMethod(method = "submit")
    private <S extends BlockEntityRenderState> void rtxremix$captureBlockEntityInPlace(
            S state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera, Operation<Void> original) {
        BlockEntity be = state instanceof RemixBlockEntityRenderStateAccess access
                ? access.rtxremix$blockEntity()
                : null;
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        boolean captured = renderer != null
                && be != null
                && be.hasLevel()
                && renderer.renderBlockEntityInPlace(be,
                        (captureMatrices, captureCollector) ->
                                original.call(state, captureMatrices, captureCollector, camera));
        if (!captured) {
            original.call(state, poseStack, submitNodeCollector, camera);
        }
    }
}
