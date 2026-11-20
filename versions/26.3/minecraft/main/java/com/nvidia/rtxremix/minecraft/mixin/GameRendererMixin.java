/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the first-person hands-and-items submission into Remix while preserving
 * Minecraft's own animation and hand-selection logic, submits the Remix frame
 * between the hand and the screen effects, brackets the GUI half of the frame
 * with a profiling scope, and has input capture follow the Remix menus at the
 * start of each frame.
 *
 * <p>26.3 frame order inside {@code GameRenderer}: {@code update} (camera),
 * {@code extract} (render states, GUI included), {@code render}: level
 * ({@code renderLevel}: {@code LevelRenderer.render}, then {@code render3dHud}:
 * hand, screen effects, debug crosshair), post effects, then {@code
 * guiRenderer.render()}. The Remix frame is submitted after the hand's
 * submission has been captured and before the screen effects are submitted, so
 * the composited image sits under the overlays and HUD as it did in 1.21.1.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @WrapOperation(
            method = "renderItemInHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V"
            ))
    private void rtxremix$captureFirstPersonModel(
            FirstPersonHandsAndItemsRenderer handsRenderer,
            float partialTicks,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            PlayerRenderState playerState,
            FirstPersonHandsAndItemsRenderState handsState,
            Operation<Void> original) {
        if (!RtxRemixMod.shouldCaptureFirstPersonModel()) {
            original.call(handsRenderer, partialTicks, poseStack, submitNodeCollector, playerState, handsState);
            return;
        }
        RemixRenderer renderer = RtxRemixMod.renderer;
        renderer.captureFirstPersonModel(
                handsRenderer, partialTicks, poseStack, submitNodeCollector, playerState, handsState);
    }

    /**
     * After the hand ({@code renderItemInHand} just returned) and before the
     * screen effects (fire, portal, block-in-face, water) are submitted: the
     * 26.3 point of the 1.21.1 inject before {@code renderScreenEffect}.
     */
    @Inject(
            method = "render3dHud",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(FLnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Z)V"
            ))
    private void rtxremix$submitBeforeScreenEffects(CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /** Covers any path that leaves {@code render3dHud} before the screen effects. */
    @Inject(method = "renderLevel()V", at = @At("TAIL"))
    private void rtxremix$submitAtRenderLevelTail(CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /**
     * The GUI tail of {@code GameRenderer.render}, from the GUI renderer's draw
     * to the method's return: HUD, screen or overlay, toasts. Unlike 1.21.1 the
     * GUI is drawn on menu-only frames too, so this zone exists every frame;
     * {@code WorldRender.END} accounts for the world half.
     */
    private ProfileScope rtxremix$guiScope;

    @Inject(
            method = "render()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"
            ))
    private void rtxremix$beginGuiScope(CallbackInfo ci) {
        rtxremix$closeGuiScope();
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$guiScope = ProfileScope.begin("GameRenderer.render.gui")
                    .plotOnClose("Java.GameRenderer.render.gui.ms");
        }
    }

    @Inject(method = "render()V", at = @At("RETURN"))
    private void rtxremix$endGuiScope(CallbackInfo ci) {
        rtxremix$closeGuiScope();
    }

    /**
     * Before the frame's camera update and the extraction that computes the GUI's
     * hover state, so that state already matches input capture.
     */
    @Inject(method = "update", at = @At("HEAD"))
    private void rtxremix$followRemixMenuVisibility(CallbackInfo ci) {
        RtxRemixMod.followRemixMenuVisibility();
    }

    private void rtxremix$closeGuiScope() {
        ProfileScope scope = rtxremix$guiScope;
        if (scope != null) {
            rtxremix$guiScope = null;
            scope.close();
        }
    }
}
