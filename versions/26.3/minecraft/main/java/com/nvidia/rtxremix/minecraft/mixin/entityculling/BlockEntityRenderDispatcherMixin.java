/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin.entityculling;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.ec.EntityCullingSupport;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Hands the renderer the block entities Entity Culling withholds from vanilla's extraction.
 *
 * <p>Entity Culling cancels {@code tryExtractRenderState} with no state for a block entity
 * no ray from the camera reaches, and by default for one outside the view. Without a state
 * the block entity is never submitted and its parts would retire, though the path tracer
 * still needs it for shadows and reflections. So a missing state is checked against
 * vanilla's own reasons for returning none; when none applies, Entity Culling withheld the
 * block entity, and the renderer keeps its parts, which show it as it was when last
 * extracted.
 *
 * <p>Applied only with Entity Culling installed and followed; see
 * {@code EntityCullingMixinPlugin}. The wrap costs every block entity extraction a call
 * through MixinExtras' {@code Operation}, which is why vanilla carries it only then.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Shadow
    private Vec3 cameraPos;

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
}
