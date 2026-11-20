/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.VanillaTerrainDraw;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;

/**
 * The frame-level hooks of the level render: where the Remix frame's world half
 * begins and ends, and the profiler phases vanilla's render is split into.
 *
 * <p>{@code LevelRenderer.render} is the 26.3 descendant of the 1.21.1 {@code
 * renderLevel}. It submits the extracted render states, builds and executes the
 * frame graph (clear, sky, main pass, outline), then compiles and uploads
 * sections. Nothing here cancels or shortens that traversal: Remix is hosted
 * inside it, and the hooks only bracket it. The vanilla phases are mirrored from
 * the method's own {@code push}/{@code popPush} calls (repositionCamera,
 * submitFeatures, prepareFeatures, setupFrameGraph, executeFrameGraph,
 * compileSections, uploadTerrainBuffers, updateSectionOcclusion); the
 * frame-graph pass names are pushed by an inspector object and are not seen
 * here, so the "main" pass shows under executeFrameGraph.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFrameMixin implements WorldRendererProfilingAccess {

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$renderScope = new ThreadLocal<>();

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    private ProfileScope rtxremix$profilerPhaseScope;
    private String rtxremix$profilerPhaseName;
    private int rtxremix$phaseTerrainDrawCallsStart;
    private int rtxremix$phaseTerrainDrawSkipsStart;

    @Inject(method = "render", at = @At("HEAD"))
    private void rtxremix$renderHead(CallbackInfo ci) {
        rtxremix$openJfrOnly(
                rtxremix$renderScope,
                "WorldRenderer.render",
                "Java.WorldRenderer.render.ms");
        VanillaTerrainDraw.resetFrame();
        rtxremix$transitionProfilerPhase("preamble");
        RtxRemixMod.onLevelRenderStart();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$renderReturn(CallbackInfo ci) {
        rtxremix$closeProfilerPhase();
        rtxremix$close(rtxremix$renderScope);
        RtxRemixMod.onLevelRenderEnd();
    }

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"
            ))
    private void rtxremix$mirrorVanillaPopPush(
            ProfilerFiller profiler,
            String name,
            Operation<Void> original) {
        original.call(profiler, name);
        rtxremix$transitionProfilerPhase(name);
    }

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V"
            ))
    private void rtxremix$mirrorVanillaPush(
            ProfilerFiller profiler,
            String name,
            Operation<Void> original) {
        original.call(profiler, name);
        rtxremix$transitionProfilerPhase(name);
    }

    private void rtxremix$transitionProfilerPhase(String vanillaPhaseName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$closeProfilerPhase();
            return;
        }
        rtxremix$closeProfilerPhase();
        rtxremix$profilerPhaseName = "WorldRenderer.phase." + rtxremix$sanitizePhase(vanillaPhaseName);
        rtxremix$phaseTerrainDrawCallsStart = VanillaTerrainDraw.calls();
        rtxremix$phaseTerrainDrawSkipsStart = VanillaTerrainDraw.skips();
        rtxremix$profilerPhaseScope = ProfileScope.begin(rtxremix$profilerPhaseName)
                .plotOnClose("Java." + rtxremix$profilerPhaseName + ".ms");
    }

    private void rtxremix$closeProfilerPhase() {
        ProfileScope scope = rtxremix$profilerPhaseScope;
        if (scope == null) {
            return;
        }
        rtxremix$profilerPhaseScope = null;
        rtxremix$annotateProfilerPhase(scope);
        scope.close();
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        rtxremix$closeProfilerPhase();
    }

    private void rtxremix$annotateProfilerPhase(ProfileScope scope) {
        LevelRenderer renderer = (LevelRenderer) (Object) this;
        int visibleChunks = renderer.visibleSections().size();
        scope.visibleChunkCount(visibleChunks);
        scope.captureCount(visibleChunks);
        if (levelRenderState != null) {
            scope.entityCount(levelRenderState.lastEntityRenderStateCount);
            scope.blockEntityCount(levelRenderState.blockEntityRenderStates.size());
            scope.blockBreakingCount(levelRenderState.blockBreakingRenderStates.size());
        }
        scope.renderLayerCalls(VanillaTerrainDraw.calls() - rtxremix$phaseTerrainDrawCallsStart);
        scope.renderLayerSkips(VanillaTerrainDraw.skips() - rtxremix$phaseTerrainDrawSkipsStart);
    }

    private static String rtxremix$sanitizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "unknown";
        }
        return phase.replace(' ', '_');
    }

    private static void rtxremix$openJfrOnly(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            String phaseName,
            String plotName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        stack.push(ProfileScope.beginJfrOnly(phaseName).plotOnClose(plotName));
    }

    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }
}
