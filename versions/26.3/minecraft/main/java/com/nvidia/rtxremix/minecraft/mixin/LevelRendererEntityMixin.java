/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixEntityPass;
import com.nvidia.rtxremix.minecraft.entity.RemixEntityRenderStateAccess;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Captures entities where vanilla submits them, instead of rendering them twice.
 *
 * <p>Vanilla extracts a render state per visible entity before the level render and
 * submits each one here, to the collector the feature renderers draw from. Replacing
 * the submit keeps Minecraft in charge of animation, render layers and modded entity
 * support, but sends the geometry to the Remix capture collector instead, so the model
 * is evaluated once for both renderers.
 *
 * <p>This traversal is frustum culled, so entities the camera cannot see are picked
 * up afterwards by the Remix pass, which needs them for shadows and reflections.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererEntityMixin {

    @WrapOperation(
            method = "submitEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"
            ))
    private void rtxremix$captureEntityInPlace(
            EntityRenderDispatcher dispatcher,
            EntityRenderState state,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector output,
            Operation<Void> original,
            PoseStack methodPoseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector methodOutput) {
        Entity entity = state instanceof RemixEntityRenderStateAccess access
                ? access.rtxremix$entity()
                : null;
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        RemixEntityPass pass = renderer == null ? null : renderer.getEntityPass();
        boolean captured = entity != null
                && pass != null
                && pass.captureInPlace(entity, state, camera, dispatcher);
        if (!captured) {
            // Remix is not drawing this entity, so vanilla's frame is what the user
            // sees and it keeps its blob shadow.
            original.call(dispatcher, state, camera, x, y, z, poseStack, output);
            return;
        }
        if (!levelRenderState.shouldShowEntityOutlines || !state.appearsGlowing()) {
            return;
        }
        // Vanilla gives an entity an outline colour only when it is glowing (or a
        // player under the spectator outlines key) and the outline post-processor is
        // usable. That pass writes the target the outline shader reads, which capturing
        // for Remix does not feed, so vanilla still has to submit it. Its blob shadow
        // must not: the GL frame is composited over the traced scene, and Remix already
        // traces the real shadow, so the blob would double it. The shadow pieces were
        // extracted for this frame only and are rebuilt at the next extraction.
        state.shadowPieces.clear();
        original.call(dispatcher, state, camera, x, y, z, poseStack, output);
    }
}
