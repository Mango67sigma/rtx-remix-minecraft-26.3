/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import com.nvidia.rtxremix.minecraft.terrain.capture.CompileRegion;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.VanillaSectionBuilds;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts chunk section builds ({@code SectionCompiler.compile}) to capture block and
 * fluid mesh data for RTX Remix <b>inline</b>, piggybacking on MC's own block loop instead
 * of running a separate pass.
 *
 * <ul>
 *   <li>{@code RenderSectionRegion.getBlockState} redirect: the one call the loop makes
 *       for every position, air included, so it is where the opacity bitmap, the face
 *       masks and the light entries are captured and where a MODEL block's quads are
 *       emitted directly to the Remix interleaved buffer. 26.x skips air before its
 *       {@code isSolidRender()} test, which is why the capture no longer hangs off
 *       that call: the boundary openness masks need the air blocks.</li>
 *   <li>{@code getRenderShape} redirect: reports every block invisible while
 *       capturing, so vanilla skips {@code tesselateBlock} (and Fabric's replacement
 *       of it) for geometry that is never drawn.</li>
 *   <li>{@code FluidRenderer.tesselate} redirect: emits fluid quads directly,
 *       skipping the vanilla buffer builders.</li>
 *   <li>RETURN inject: finalises the section mesh data and enqueues it
 *       for the render thread.</li>
 * </ul>
 *
 * <p>Every hook acts only in a build that latched capture on at HEAD, so a build in vanilla
 * mode never touches the Remix emitter, whatever state an earlier failed build left it in.
 */
@Mixin(SectionCompiler.class)
public class SectionBuilderMixin {

    private static final ThreadLocal<ProfileScope> rtxremix$tracyOuter = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$tracyBlockLoop = new ThreadLocal<>();
    private static final ThreadLocal<BlockDataQueue.CaptureToken> rtxremix$blockDataCaptureToken = new ThreadLocal<>();

    /**
     * Whether this compile() is capturing for Remix, latched once at HEAD.
     *
     * <p>{@code rtxEnabled} is volatile so worker threads see a toggle promptly,
     * which means every redirect below would otherwise read it independently and
     * a toggle could land <em>between</em> two of them inside one build, routing
     * some blocks of a section into the Remix emitter and the rest into vanilla's
     * GL path. Reading one latched value keeps a build wholly in one mode;
     * {@code setRtxEnabled} forces a reload, so sections built in the losing mode
     * are rebuilt immediately afterwards.
     *
     * <p>The latch is taken through {@link VanillaSectionBuilds}: a build that
     * latched capture off reads client light on this worker, and the client light
     * storage may publish its live map only while no such build is running.
     */
    private static final ThreadLocal<Boolean> rtxremix$captureForBuild =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Latched RTX capture state for the build in progress on this thread. */
    private static boolean rtxremix$capturing() {
        return rtxremix$captureForBuild.get();
    }

    @Shadow
    @Final
    private BlockStateModelSet blockModelSet;

    @Shadow
    @Final
    private FluidStateModelSet fluidModelSet;

    @Shadow
    @Final
    private BlockColors blockColors;

    // HEAD: init per-section emission state
    @Inject(method = "compile", at = @At("HEAD"))
    private void rtxremix$onBuildHead(
            SectionPos sectionPos,
            RenderSectionRegion renderRegion,
            VertexSorting vertexSorter,
            SectionBufferBuilderPack allocatorStorage,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {

        // Latched before any early return below, and unconditionally, so a value
        // left over from this thread's previous build cannot decide this one.
        boolean capturing = VanillaSectionBuilds.beginBuild(RtxRemixMod::isRtxEnabled);
        rtxremix$captureForBuild.set(capturing);
        if (!capturing) return;

        // The region was stamped when its snapshot was taken on the render thread, so the
        // sequence orders captures of one section by the world state they read.
        long sequence = (Object) renderRegion instanceof CompileRegion region
                ? region.rtxremix$captureSequence()
                : BlockDataQueue.nextCaptureSequence();
        rtxremix$blockDataCaptureToken.set(
                BlockDataQueue.beginCapture(sectionPos.getX(), sectionPos.getZ(), sequence));
        rtxremix$tracyOuter.set(ProfileScope.begin("SectionBuilder"));
        rtxremix$tracyBlockLoop.set(null);

        if (renderRegion == null) return;

        BlockTextureManager tex = null;
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null) {
            tex = mod.getRenderer().getTextureManager();
        }

        BlockQuadEmitter.threadLocal().beginSection(sectionPos, tex, this.blockModelSet, this.fluidModelSet, this.blockColors);
        rtxremix$tracyBlockLoop.set(ProfileScope.begin("block loop"));
    }

    /**
     * getBlockState: capture opacity / face masks / lights / quads for every position.
     *
     * <p>Vanilla reads the state at {@code SectionCompiler.java:78}, before the per-block try
     * that turns a tesselation failure into a crash report, so a failure in the capture would
     * escape that wrapper. {@code SectionRenderDispatcher.runTask} then requeues the task
     * silently on a {@code NullPointerException} without returning its buffer pack, and a few
     * such failures stop all section compilation. The capture therefore wraps itself the way
     * vanilla wraps the tesselation: the section is abandoned and the failure becomes a
     * {@link ReportedException}, which {@code runTask} reports as a delayed crash.
     */
    @Redirect(method = "compile",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState rtxremix$captureBlock(RenderSectionRegion region, BlockPos pos) {
        BlockState state = region.getBlockState(pos);
        if (!rtxremix$capturing()) {
            return state;
        }
        BlockQuadEmitter emitter = BlockQuadEmitter.current();
        if (emitter == null || !emitter.isActive()) {
            return state;
        }
        try {
            // The same cached flag vanilla feeds its VisGraph a few lines later.
            boolean opaque = state.isSolidRender();
            emitter.processBlock(state, region, pos, opaque);
            // Vanilla hands only MODEL blocks to tesselateBlock; ENTITYBLOCK_ANIMATED blocks
            // belong to their block-entity renderer.
            if (BlockQuadEmitter.getRenderTypeCached(state) == RenderShape.MODEL) {
                emitter.emitBlock(state, pos, region);
            }
        } catch (Throwable t) {
            emitter.abortSection();
            rtxremix$endBuild();
            CrashReport report = CrashReport.forThrowable(t, "Capturing block for RTX Remix");
            CrashReportCategory category = report.addCategory("Block being captured");
            CrashReportCategory.populateBlockDetails(category, region, pos, state);
            throw new ReportedException(report);
        }
        return state;
    }

    // While capturing, the block's quads were already emitted above, so vanilla's raster
    // path for it (layer buffer, tesselateBlock, Fabric's alt renderer) is dead work.
    // Reporting the block invisible makes vanilla skip all of it.
    @Redirect(method = "compile",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape rtxremix$renderTypeForVanilla(BlockState state) {
        return rtxremix$capturing()
                ? RenderShape.INVISIBLE
                : BlockQuadEmitter.getRenderTypeCached(state);
    }

    // FluidRenderer.tesselate: emit fluid quads for Remix, skip the vanilla builders
    @Redirect(method = "compile",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
    private void rtxremix$redirectFluid(FluidRenderer fluidRenderer,
            BlockAndTintGetter world, BlockPos pos, FluidRenderer.Output output,
            BlockState blockState, FluidState fluidState) {
        if (rtxremix$capturing()) {
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            if (!emitter.isActive()) return;
            // Inside vanilla's per-block try: a failure here is reported by vanilla, and the
            // next capturing build on this thread releases what this one staged.
            emitter.emitFluid(pos, world, fluidState);
        } else {
            fluidRenderer.tesselate(world, pos, output, blockState, fluidState);
        }
    }

    // RETURN: finalise mesh data and enqueue
    @Inject(method = "compile", at = @At("RETURN"))
    private void rtxremix$onBuildReturn(
            SectionPos sectionPos,
            RenderSectionRegion renderRegion,
            VertexSorting vertexSorter,
            SectionBufferBuilderPack allocatorStorage,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {

        try {
            if (!rtxremix$capturing()) return;
            BlockQuadEmitter emitter = BlockQuadEmitter.current();
            if (emitter == null || !emitter.isActive()) return;

            ProfileScope blockScope = rtxremix$tracyBlockLoop.get();
            rtxremix$tracyBlockLoop.remove();
            if (blockScope != null) blockScope.close();

            // The token is set at compile HEAD before beginSection, so an active emitter
            // implies one exists.
            SectionBlockData blockData = emitter.finishSection(sectionPos);
            BlockDataQueue.enqueue(blockData, rtxremix$blockDataCaptureToken.get());
        } finally {
            rtxremix$endBuild();
        }
    }

    /**
     * Closes this thread's build: its profiling scopes, capture token and latch. Called at
     * RETURN, and by the block capture before it throws, since RETURN then never runs. A
     * vanilla build that throws skips RETURN and stays counted; vanilla turns a section build
     * exception into a client crash, so the stale count cannot outlive the session.
     */
    @Unique
    private static void rtxremix$endBuild() {
        ProfileScope blockScope = rtxremix$tracyBlockLoop.get();
        if (blockScope != null) blockScope.close();
        ProfileScope outerScope = rtxremix$tracyOuter.get();
        if (outerScope != null) outerScope.close();
        rtxremix$tracyOuter.remove();
        rtxremix$tracyBlockLoop.remove();
        rtxremix$blockDataCaptureToken.remove();
        VanillaSectionBuilds.endBuild(rtxremix$capturing());
        rtxremix$captureForBuild.remove();
    }
}
