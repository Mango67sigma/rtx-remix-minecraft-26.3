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
import com.nvidia.rtxremix.minecraft.entity.ec.EntityCullingSupport;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
 *
 * <p>Entity Culling cancels {@code tryExtractRenderState} with no state for a block entity
 * no ray from the camera reaches, and by default for one outside the view. Without a state
 * the block entity is never submitted and its parts would retire, though the path tracer
 * still needs it for shadows and reflections. So while the capture follows Entity Culling,
 * a missing state is checked against vanilla's own reasons for returning none; when none
 * applies, Entity Culling withheld the block entity, and the renderer keeps its parts.
 * Those parts show the block entity as it was when last extracted.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Shadow
    private Vec3 cameraPos;

    @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
    private <E extends BlockEntity> void rtxremix$attachBlockEntity(
            E blockEntity, float partialTicks,
            ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean isGloballyRendered,
            CallbackInfoReturnable<BlockEntityRenderState> cir) {
        if (cir.getReturnValue() instanceof RemixBlockEntityRenderStateAccess access) {
            access.rtxremix$setBlockEntity(blockEntity);
        }
    }

    /**
     * A wrap rather than a RETURN injector: Entity Culling cancels the method at its head,
     * and a cancelled callback returns through code of its own, which no RETURN injector
     * reaches. The wrap encloses the whole body, its cancellation included.
     */
    @WrapMethod(method = "tryExtractRenderState")
    private BlockEntityRenderState rtxremix$keepWithheldBlockEntity(
            BlockEntity blockEntity, float partialTicks,
            ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean isGloballyRendered,
            Operation<BlockEntityRenderState> original) {
        BlockEntityRenderState state =
                original.call(blockEntity, partialTicks, breakProgress, isGloballyRendered);
        if (state == null
                && EntityCullingSupport.active()
                && rtxremix$passesVanillaGuards(blockEntity, isGloballyRendered)) {
            RtxRemixMod mod = RtxRemixMod.getInstance();
            RemixRenderer renderer = mod == null ? null : mod.getRenderer();
            if (renderer != null) {
                renderer.keepWithheldBlockEntity(blockEntity);
            }
        }
        return state;
    }

    /**
     * Whether none of vanilla's own reasons to extract no state applies: the block entity
     * has a renderer, a level and a block that still carries it, it is asked for from the
     * list its renderer belongs to, and it lies within the renderer's view distance. These
     * are the early returns of 26.3's {@code tryExtractRenderState}, asked in its order.
     */
    @Unique
    private boolean rtxremix$passesVanillaGuards(BlockEntity blockEntity, boolean isGloballyRendered) {
        BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer =
                ((BlockEntityRenderDispatcher) (Object) this).getRenderer(blockEntity);
        return renderer != null
                && blockEntity.hasLevel()
                && blockEntity.getType().isValid(blockEntity.getBlockState())
                && renderer.shouldRenderOffScreen() == isGloballyRendered
                && renderer.shouldRender(blockEntity, cameraPos);
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
