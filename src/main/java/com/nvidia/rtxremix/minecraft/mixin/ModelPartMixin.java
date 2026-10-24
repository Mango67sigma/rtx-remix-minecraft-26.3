/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.RecyclingMatrixStack;
import com.nvidia.rtxremix.minecraft.entity.RemixCapturingConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SpriteCoordinateExpander;


/**
 * Routes vanilla model-part rendering through the capture's allocation-free paths.
 *
 * <p>{@code translateAndRotate} on a {@link RecyclingMatrixStack} applies the part
 * transform through the stack's pooled quaternion. The math is vanilla's, read from
 * the same pivot, rotation and scale fields, so a renderer that sets a pivot directly,
 * as the banner does for its flag, is honoured. The entity pass and the block-entity
 * pass both render on such a stack.
 *
 * <p>{@code compile} on a single-cube part borrows the capturing consumer's singleton
 * iterator instead of allocating vanilla's. The consumer is found directly or behind
 * the {@link SpriteCoordinateExpander} that sprite-atlas models draw through, the same
 * one-step look-through {@code CuboidMixin} applies. Sprite-wrapped parts therefore
 * reuse the iterator too; a wrapper around anything else is left to vanilla.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin {

    /**
     * The class of the unmodifiable single-element list vanilla bakes a one-cube part's
     * cubes into ({@code Stream.toList()} and {@code List.of(x)} share it). Only such a
     * list borrows the iterator: its iteration cannot be observed by anyone else.
     */
    @Unique
    private static final Class<?> rtxremix$SINGLE_IMMUTABLE_LIST = List.of(new Object()).getClass();

    @Shadow public float x;
    @Shadow public float y;
    @Shadow public float z;
    @Shadow public float xRot;
    @Shadow public float yRot;
    @Shadow public float zRot;
    @Shadow public float xScale;
    @Shadow public float yScale;
    @Shadow public float zScale;

    @Inject(
            method = "translateAndRotate(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void rtxremix$applyPooledRotation(PoseStack poseStack, CallbackInfo ci) {
        if (!(poseStack instanceof RecyclingMatrixStack recyclingMatrices)) {
            return;
        }

        recyclingMatrices.applyModelPartTransform(
                x, y, z,
                xRot, yRot, zRot,
                xScale, yScale, zScale);
        ci.cancel();
    }

    @WrapOperation(
            method = "compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<ModelPart.Cube> rtxremix$reuseSingletonCuboidIterator(
            List<ModelPart.Cube> cubes,
            Operation<Iterator<ModelPart.Cube>> original,
            PoseStack.Pose pose,
            VertexConsumer builder,
            int lightCoords,
            int overlayCoords,
            int color) {
        VertexConsumer target = builder instanceof SpriteCoordinateExpander wrapped
                ? wrapped.delegate()
                : builder;
        // Local reads first: the cube count and list type reject group and
        // multi-cube parts before the capture check pays for its thread-local lookup.
        if (target instanceof RemixCapturingConsumer consumer
                && cubes.size() == 1
                && cubes.getClass() == rtxremix$SINGLE_IMMUTABLE_LIST
                && consumer.isRemixCaptureActive()) {
            Iterator<ModelPart.Cube> iterator =
                    consumer.acquireSingletonCuboidIterator(cubes.getFirst());
            if (iterator != null) {
                return iterator;
            }
        }
        return original.call(cubes);
    }
}
