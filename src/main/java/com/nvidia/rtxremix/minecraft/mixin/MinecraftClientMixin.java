/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.nvidia.rtxremix.minecraft.GraphicsBackendPolicy;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.util.thread.BlockableEventLoop;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into Minecraft's lifecycle: the graphics backend choice, the frame
 * boundaries the Remix renderer and benchmark follow, fine profiling scopes
 * through {@code runTick}, {@code renderFrame} and {@code tick}, and the close.
 *
 * <p>In 26.3 one frame is {@code runTick} (tasks, ticks, sound, mouse) around
 * {@code renderFrame} (surface acquire, camera update, extraction, {@code
 * GameRenderer.render}, swapchain blit, submit, present, frame limiter). The
 * render-phase skeleton spans both methods; the per-frame scopes are thread
 * locals that close idempotently, and the hooks inside {@code renderFrame} act
 * only inside a {@code runTick} frame because the loading screens call {@code
 * renderFrame} on their own.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    private static final Logger rtxremix$LOGGER = LoggerFactory.getLogger("RtxRemixMod");

    private static final ThreadLocal<ProfileScope> rtxremix$renderScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$renderPhaseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$tickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$tickPhaseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$preGamePrepScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$beginRenderTickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$scheduledTasksScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$textureTickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$soundScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$mouseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$updateScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$extractScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$gpuAsyncScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$blitScope = new ThreadLocal<>();
    private static final ThreadLocal<Long> rtxremix$renderStartNanos = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> rtxremix$inRunTick = new ThreadLocal<>();

    /** True while the window surface could not be configured or acquired (iconified window). */
    @Shadow
    private boolean surfaceIsInvalid;

    /**
     * Makes the game start on the OpenGL backend when no launch argument chose
     * one. Minecraft reads {@code GameConfig.GameData.forcedGraphicsApi} once in
     * its constructor; a null there lets the in-game preference pick Vulkan where
     * it is available, which the Remix GL interop cannot work on. Supplying the
     * force here is the same path {@code --graphicsBackend OPENGL} takes, so the
     * log and the Video Settings screen report the backend as forced.
     */
    @ModifyExpressionValue(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/main/GameConfig$GameData;forcedGraphicsApi:Lnet/minecraft/client/PreferredGraphicsApi;",
                    opcode = Opcodes.GETFIELD
            ))
    private PreferredGraphicsApi rtxremix$forceOpenGlBackend(PreferredGraphicsApi forced) {
        if (forced != null || !GraphicsBackendPolicy.FORCE_OPENGL) {
            return forced;
        }
        rtxremix$LOGGER.info("RTX Remix needs the OpenGL graphics backend; forcing it (set -D{}=false to leave the choice to the launcher)",
                GraphicsBackendPolicy.FORCE_PROPERTY);
        return PreferredGraphicsApi.OPENGL;
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void rtxremix$renderHead(boolean tick, CallbackInfo ci) {
        rtxremix$inRunTick.set(Boolean.TRUE);
        ProfileScope.beginFrame();
        rtxremix$renderStartNanos.set(System.nanoTime());
        rtxremix$openRenderScope();
        rtxremix$transitionRenderPhase("MinecraftClient.render.preGameRenderer");
        rtxremix$openScope(rtxremix$preGamePrepScope, "MinecraftClient.render.preGamePrep");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I"
        ),
        require = 0
    )
    private void rtxremix$beforeBeginRenderTick(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$openScope(rtxremix$beginRenderTickScope, "MinecraftClient.render.beginRenderTick");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterBeginRenderTick(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"
        ),
        require = 0
    )
    private void rtxremix$beforeRunTasks(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        ProfileScope.beginClientTaskDrain("MinecraftClient", rtxremix$getTaskCount());
        rtxremix$openScope(rtxremix$scheduledTasksScope, "MinecraftClient.render.runTasks");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;runAllTasks()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterRunTasks(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
    }

    /** Texture animation ticks moved from {@code tick} to {@code runTick} in 26.x. */
    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeTextureTick(boolean tick, CallbackInfo ci) {
        rtxremix$openScope(rtxremix$textureTickScope, "MinecraftClient.render.textureTick");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterTextureTick(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$textureTickScope);
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;updateSource(Lnet/minecraft/client/Camera;)V"
        ),
        require = 0
    )
    private void rtxremix$beforeSoundListener(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$closeScope(rtxremix$textureTickScope);
        rtxremix$openScope(rtxremix$soundScope, "MinecraftClient.render.soundListener");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;updateSource(Lnet/minecraft/client/Camera;)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterSoundListener(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$soundScope);
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V"
        ),
        require = 0
    )
    private void rtxremix$beforeMouseTick(boolean tick, CallbackInfo ci) {
        rtxremix$openScope(rtxremix$mouseScope, "MinecraftClient.render.mouseTick");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterMouseTick(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$mouseScope);
    }

    /**
     * Immediately before the frame renders. {@code renderFrame} updates the
     * camera ({@code GameRenderer.update}) before it extracts and renders, so
     * camera poses applied here land in this frame's {@link net.minecraft.client.Camera}.
     */
    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"
        )
    )
    private void rtxremix$beforeRenderFrame(boolean tick, CallbackInfo ci) {
        rtxremix$closeRunTickChildScopes();
        rtxremix$transitionRenderPhase("MinecraftClient.render.gameRenderer");

        // Apply interactive or benchmark camera poses before GameRenderer.update
        // builds Minecraft's interpolated Camera for this frame.
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null) {
            renderer.onMinecraftWorldRenderFrameStart((Minecraft) (Object) this, tick);
        }
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void rtxremix$afterRenderFrame(boolean tick, CallbackInfo ci) {
        rtxremix$closeRenderFrameChildScopes();
        rtxremix$transitionRenderPhase("MinecraftClient.render.postFrame");
    }

    // Tracy frames come from the runtime's FrameMark inside Present; a second
    // mark here would split every frame at the present boundary.
    @Inject(method = "runTick", at = @At("RETURN"))
    private void rtxremix$renderReturn(boolean tick, CallbackInfo ci) {
        rtxremix$closeRunTickChildScopes();
        rtxremix$closeRenderFrameChildScopes();
        rtxremix$closeRenderPhase();
        rtxremix$closeRenderScope();
        rtxremix$inRunTick.remove();
        Long startedAt = rtxremix$renderStartNanos.get();
        if (startedAt != null) {
            rtxremix$renderStartNanos.remove();
            long fullFrameNanos = System.nanoTime() - startedAt;
            ProfileScope.emitFrameLedger(fullFrameNanos);
            RemixRenderer renderer = RtxRemixMod.renderer;
            if (renderer != null) {
                renderer.onMinecraftRenderFrameEnd(fullFrameNanos);
            }
        }
    }

    // --- renderFrame: the frame proper ---

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"
        ),
        require = 0
    )
    private void rtxremix$beforeUpdate(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$openScope(rtxremix$updateScope, "MinecraftClient.render.update");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"
        ),
        require = 0
    )
    private void rtxremix$beforeExtract(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$closeScope(rtxremix$updateScope);
        rtxremix$openScope(rtxremix$extractScope, "MinecraftClient.render.extract");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;executePendingTasks()V"
        ),
        require = 0
    )
    private void rtxremix$beforeGpuAsync(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$closeScope(rtxremix$updateScope);
        rtxremix$closeScope(rtxremix$extractScope);
        rtxremix$openScope(rtxremix$gpuAsyncScope, "MinecraftClient.render.gpuAsync");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"
        ),
        require = 0
    )
    private void rtxremix$beforeGameRenderer(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$closeScope(rtxremix$updateScope);
        rtxremix$closeScope(rtxremix$extractScope);
        rtxremix$closeScope(rtxremix$gpuAsyncScope);
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterGameRenderer(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$transitionRenderPhase("MinecraftClient.render.postGameRenderer");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"
        ),
        require = 0
    )
    private void rtxremix$beforeBlit(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$openScope(rtxremix$blitScope, "MinecraftClient.render.blitFramebuffer");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterBlit(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$closeScope(rtxremix$blitScope);
    }

    /** The present is where Remix presents instead ({@code FrontendGpuSurfaceMixin}); it is the frame's anchor. */
    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"
        )
    )
    private void rtxremix$beforePresent(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$closeRenderFrameChildScopes();
        rtxremix$transitionRenderPhase("MinecraftClient.render.swapBuffers");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V",
            shift = At.Shift.AFTER
        )
    )
    private void rtxremix$afterPresent(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$transitionRenderPhase("MinecraftClient.render.frameLimit");
    }

    /**
     * Vanilla skips the swapchain blit and the present while the surface is
     * invalid (the window is iconified), but the frame, and with it the Remix
     * submit, still runs. In 1.21.1 the buffer swap ran unconditionally, so every
     * submit was followed by a present; presenting here on the frames vanilla
     * skips keeps that pairing and the GL-Vulkan semaphore sequence intact.
     * {@code LevelRenderer.endFrame} is the first unconditional call after the
     * present block.
     */
    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V"
        )
    )
    private void rtxremix$presentWhenSurfaceSkipped(boolean tick, CallbackInfo ci) {
        if (!surfaceIsInvalid) {
            return;
        }
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.presentFrameVk();
        }
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"
        ),
        require = 0
    )
    private void rtxremix$beforeLimitDisplayFps(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$transitionRenderPhase("MinecraftClient.render.limitDisplayFPS");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterLimitDisplayFps(boolean tick, CallbackInfo ci) {
        if (!rtxremix$isInRunTick()) {
            return;
        }
        rtxremix$transitionRenderPhase("MinecraftClient.render.afterFrameLimit");
    }

    // --- tick ---

    @Inject(method = "tick", at = @At("HEAD"))
    private void rtxremix$tickHead(CallbackInfo ci) {
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$tickScope.set(ProfileScope.begin("MinecraftClient.tick")
                    .plotOnClose("Java.MinecraftClient.tick.ms"));
        }
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeHudTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.hud");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterHudTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pick(F)V"
        ),
        require = 0
    )
    private void rtxremix$beforeCrosshairTarget(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.crosshairTarget");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pick(F)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterCrosshairTarget(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeInteractionTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.interactionManager");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterInteractionTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V"
        ),
        require = 0
    )
    private void rtxremix$beforeInputEvents(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.inputEvents");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterInputEvents(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeGameRendererTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.gameRenderer");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterGameRendererTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V"
        ),
        require = 0
    )
    private void rtxremix$beforeWorldEntitiesTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.worldEntities");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterWorldEntitiesTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    /** Block entities tick separately from entities since 26.x; the former {@code LevelRenderer.tick} phase. */
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V"
        ),
        require = 0
    )
    private void rtxremix$beforeWorldBlockEntitiesTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.worldBlockEntities");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterWorldBlockEntitiesTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V"
        ),
        require = 0
    )
    private void rtxremix$beforeWorldTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.world");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterWorldTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V"
        ),
        require = 0
    )
    private void rtxremix$beforeRandomBlockDisplayTicks(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.randomBlockDisplay");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterRandomBlockDisplayTicks(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeParticleManagerTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.particles");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterParticleManagerTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;tick(Z)V"
        ),
        require = 0
    )
    private void rtxremix$beforeSoundTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.sound");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;tick(Z)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterSoundTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/KeyboardHandler;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeKeyboardTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.keyboard");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/KeyboardHandler;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterKeyboardTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$tickReturn(CallbackInfo ci) {
        rtxremix$closeTickPhase();
        ProfileScope scope = rtxremix$tickScope.get();
        if (scope != null) {
            rtxremix$tickScope.remove();
            scope.close();
        }
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void rtxremix$closeTail(CallbackInfo ci) {
        RtxRemixMod.shutdownAfterMinecraftClose();
    }

    private static boolean rtxremix$isInRunTick() {
        return rtxremix$inRunTick.get() != null;
    }

    private static void rtxremix$transitionRenderPhase(String phaseName) {
        rtxremix$closeRenderPhase();
        // The phases partition the frame into consecutive spans that give it a
        // top-level Tracy skeleton. Injected child scopes close before their phase
        // transitions; when a nested render (world join or disconnect from inside
        // tick) closes a phase under an open child, ProfileScope defers the Tracy
        // end so the zones stay well-formed.
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$renderPhaseScope.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void rtxremix$openRenderScope() {
        rtxremix$closeRenderScope();
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$renderScope.set(ProfileScope.beginJfrOnly("MinecraftClient.render")
                    .plotOnClose("Java.MinecraftClient.render.ms"));
        }
    }

    private static void rtxremix$closeRenderScope() {
        ProfileScope scope = rtxremix$renderScope.get();
        if (scope != null) {
            rtxremix$renderScope.remove();
            scope.close();
        }
    }

    private static void rtxremix$closeRenderPhase() {
        ProfileScope scope = rtxremix$renderPhaseScope.get();
        if (scope != null) {
            rtxremix$renderPhaseScope.remove();
            scope.close();
        }
    }

    private static void rtxremix$openTickPhase(String phaseName) {
        rtxremix$closeTickPhase();
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$tickPhaseScope.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void rtxremix$closeTickPhase() {
        ProfileScope scope = rtxremix$tickPhaseScope.get();
        if (scope != null) {
            rtxremix$tickPhaseScope.remove();
            scope.close();
        }
    }

    private static void rtxremix$openScope(ThreadLocal<ProfileScope> holder, String phaseName) {
        rtxremix$closeScope(holder);
        // Guard the begin() call and the plot-name concatenation: these run
        // every frame for each render sub-phase, so skipping them when no Java
        // scope is enabled avoids per-frame string allocation on the hot path.
        if (ProfileScope.isJavaScopeEnabled()) {
            holder.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void rtxremix$closeScope(ThreadLocal<ProfileScope> holder) {
        ProfileScope scope = holder.get();
        if (scope != null) {
            holder.remove();
            scope.close();
        }
    }

    private static void rtxremix$closeRunTickChildScopes() {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$closeScope(rtxremix$textureTickScope);
        rtxremix$closeScope(rtxremix$soundScope);
        rtxremix$closeScope(rtxremix$mouseScope);
    }

    private static void rtxremix$closeRenderFrameChildScopes() {
        rtxremix$closeScope(rtxremix$updateScope);
        rtxremix$closeScope(rtxremix$extractScope);
        rtxremix$closeScope(rtxremix$gpuAsyncScope);
        rtxremix$closeScope(rtxremix$blitScope);
    }

    private int rtxremix$getTaskCount() {
        // O(n) in the queued task backlog; skip it when nothing records the value.
        if (!ProfileScope.isClientTaskProfilingEnabled()) {
            return 0;
        }
        return ((BlockableEventLoop<?>)(Object)this).getPendingTasksCount();
    }

    private static void rtxremix$closeClientTaskDrain() {
        Minecraft client = Minecraft.getInstance();
        int taskCount = 0;
        if (client != null && ProfileScope.isClientTaskProfilingEnabled()) {
            taskCount = ((BlockableEventLoop<?>)client).getPendingTasksCount();
        }
        ProfileScope.endClientTaskDrain(taskCount);
    }
}
