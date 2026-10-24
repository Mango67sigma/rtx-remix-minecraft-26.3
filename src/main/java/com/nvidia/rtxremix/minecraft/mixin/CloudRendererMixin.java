/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla's cloud draw while the Remix cloud renderer replaces it.
 *
 * <p>1.21.1 drew clouds in one {@code LevelRenderer.renderClouds} call, which the mod cancelled at
 * its head. 26.3 splits the work across the frame: {@code LevelRenderer.prepareTranslucents}
 * calls {@link CloudRenderer#prepare} to rebuild the cell buffer and write the cloud UBO, then
 * the main pass draws through {@link CloudRenderer#render} (classic transparency) or
 * {@link CloudRenderer#renderOit} once per OIT stage. The decision is latched in {@code prepare}
 * so that one frame either draws vanilla clouds in every pass or in none, even if the Remix
 * cloud renderer fails over between the passes.
 *
 * <p>Nothing has to be replayed for the cancelled calls: {@code prepare} touches only the cloud
 * renderer's own buffers and asks the shared quad index buffer to grow, and {@code render} and
 * {@code renderOit} change state only inside a render pass the caller owns or (for OIT) one they
 * open and close themselves. Skipping {@code prepare} leaves the cell buffer as it was, so when
 * vanilla clouds return {@code prepare} rebuilds whatever changed meanwhile.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {

    @Unique
    private boolean rtxremix$suppressed;

    @Inject(
            method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void rtxremix$skipVanillaCloudPrepare(
            int color,
            CloudStatus cloudStatus,
            float bottomY,
            int range,
            Vec3 cameraPosition,
            long gameTime,
            float partialTicks,
            CallbackInfo ci) {
        rtxremix$suppressed = rtxremix$remixReplacesClouds();
        if (rtxremix$suppressed) {
            ci.cancel();
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void rtxremix$skipVanillaCloudDraw(CloudStatus cloudStatus, RenderPass renderPass, CallbackInfo ci) {
        if (rtxremix$suppressed) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaCloudOitDraw(
            CloudStatus cloudStatus,
            OitStage stage,
            GpuTextureView mainDepthTextureView,
            OitRenderPassProvider.Parameters params,
            CallbackInfo ci) {
        if (rtxremix$suppressed) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean rtxremix$remixReplacesClouds() {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            return false;
        }
        RemixRenderer renderer = RtxRemixMod.getInstance().getRenderer();
        return renderer != null
                && renderer.getCloudRenderer() != null
                && renderer.getCloudRenderer().shouldSuppressVanillaClouds();
    }
}
