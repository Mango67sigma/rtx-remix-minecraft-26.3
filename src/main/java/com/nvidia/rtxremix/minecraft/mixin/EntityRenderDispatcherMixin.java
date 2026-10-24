/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixEntityRenderStateAccess;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Brackets each entity submit with a capture boundary on the thread-local
 * {@link EntityRenderContext}, and names the entity on the render state vanilla
 * extracts from it.
 *
 * <p>{@code EntityRenderDispatcher.submit()} opens the boundary at HEAD with the
 * remix-pass flag, decided by whether the collector is the capture's own, and
 * closes it at RETURN. Nesting is the context's concern: an entity submitted from
 * inside another submit appends to the enclosing capture instead of starting its
 * own.
 *
 * <p>{@code extractEntity()} is where vanilla turns an entity into the render state
 * it submits a moment later. The state forgets the entity; the capture keys its
 * retained instances and its frame bookkeeping by it, so the entity is attached to
 * the state as it is returned.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void rtxremix$attachEntity(
            E entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        if (cir.getReturnValue() instanceof RemixEntityRenderStateAccess access) {
            access.rtxremix$setEntity(entity);
        }
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends EntityRenderState> void rtxremix$onSubmitStart(
            S renderState, CameraRenderState camera,
            double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        EntityRenderContext.get().begin(submitNodeCollector instanceof RemixSubmitNodeCollector);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends EntityRenderState> void rtxremix$onSubmitEnd(
            S renderState, CameraRenderState camera,
            double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        EntityRenderContext.get().end();
    }
}
