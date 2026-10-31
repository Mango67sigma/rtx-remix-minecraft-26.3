/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Captures block-entity geometry where vanilla submits it and submits it through
 * the entity submitter with the {@link BlockEntity} as owner.
 *
 * <p>{@code BlockEntityRenderDispatcherMixin} routes each vanilla submit to
 * {@code RemixRenderer.renderBlockEntityInPlace}, which calls {@link #capture}. The
 * block entity's renderer is run on this pass's own identity {@link RecyclingMatrixStack}
 * instead of vanilla's camera-relative one, and into this pass's own
 * {@link RemixSubmitNodeCollector}, which executes every submit at once into the
 * capture consumers. The captured pivots therefore hold only the renderer's block
 * rotation and animation, and the instance root is a translation by the block
 * position, an integer that is exact in float. Vanilla still decides what is
 * rendered: {@code LevelExtractor} extracts only the block entities of
 * frustum-visible sections that pass their renderer's distance check, so an
 * out-of-range block entity never reaches the submit.
 *
 * <p>Block entities share the submitter's retained registry, mesh pool and material
 * cache with entities, so this pass holds no native state. A block entity vanilla does
 * not submit in a frame, because it is culled, out of range or unloaded, is simply
 * absent, and the submitter's {@code endFrame} retires its parts. The exception is a
 * block entity Entity Culling withheld where vanilla would have extracted it: one no ray
 * from the camera reaches, and by default one outside the view. It still casts shadows
 * and shows in reflections, so {@link #keepWithheld} keeps its parts as they were when
 * it was last captured. There is no frame bracket of its own: capture runs inside the
 * submitter's open frame, and {@link #finishFrame} only reports the frame's counters.
 * Shadows of a spawner's display entity never reach the scene: the capture collector
 * drops shadow submits, and Remix traces the real one.
 */
public final class RemixBlockEntityPass {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixBlockEntityPass.class);

    /**
     * Placed banners and shields are static world geometry, so their patterns are
     * static decals over the dyed base. The same cuboids carried in a hand stay plain
     * geometry, which is why the category is the capturing pass's choice rather than
     * the submitter's.
     */
    static final RemixEntitySubmitter.PartCategoryPolicy STATIC_WORLD_CANVASES =
            role -> role == RemixEntitySubmitter.PatternCanvasRole.PATTERN
                    ? InstanceCategoryBit.DECAL_STATIC
                    : 0;

    /**
     * Vanilla's dispatcher submit with the matrix stack and collector left open, so the
     * pass can supply its own. The mixin's {@code Operation} handle stays in the mixin.
     */
    @FunctionalInterface
    public interface BlockEntityRenderCall {
        void render(PoseStack matrices, SubmitNodeCollector collector);
    }

    /**
     * Totals for one frame's block entities. {@code count} is the block entities in the
     * frame's scene, captured or kept, and {@code culled} those of them kept because
     * Entity Culling withheld them. Part and mesh-create counts are this pass's own
     * submits, taken as deltas of the submitter's running totals, which also carry
     * entities; a kept block entity submits nothing. Times are microseconds, sized for
     * the counters they feed.
     */
    public record FrameStats(int count, int culled, int parts, int meshCreates,
                             int captureMicros, int submitMicros) {}

    private final RecyclingMatrixStack matrices = new RecyclingMatrixStack();
    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final RemixSubmitNodeCollector collector = new RemixSubmitNodeCollector(provider);
    private final IdentityHashMap<BlockEntityType<?>, String> typeKeyCache = new IdentityHashMap<>();
    private final FailureLogThrottle failures = new FailureLogThrottle(LOGGER, "Block entity");
    // One buffer suffices: the submit consumes the bones before the next capture
    // overwrites them.
    private float[] reusableBoneData = new float[0];

    /**
     * Block entities Entity Culling withheld from this frame's extraction. Vanilla extracts
     * before the frame's capture window opens, so they wait here for {@link #keepWithheld}.
     */
    private final List<BlockEntity> withheld = new ArrayList<>();

    private int frameCount, frameCulled, frameParts, frameMeshCreates;
    private long frameCaptureNanos, frameSubmitNanos;

    /**
     * Runs one block entity's renderer on the identity stack into the capture
     * collector and submits the capture inside the submitter's open frame.
     *
     * <p>Submitting before the next block entity renders is what lets the provider's
     * consumers recycle: a layer's data is consumed before anything can overwrite it.
     */
    public void capture(BlockEntity be, BlockEntityRenderCall call, RemixEntitySubmitter submitter) {
        long captureStart = System.nanoTime();
        EntityRenderContext ctx = EntityRenderContext.get();
        provider.beginEntity();
        matrices.pushPose();
        ctx.begin(true);
        List<RemixVertexConsumerProvider.CapturedLayer> layers;
        try {
            call.render(matrices, collector);
            layers = provider.endEntity();
        } catch (Exception e) {
            failures.noteFailure("capturing", "block entity " + typeKey(be), be.getBlockPos(), e);
            return;
        } finally {
            // Both restore even if the renderer threw mid-traversal: the identity
            // root returns the stack to its root, and a capture left open would make
            // the next one look nested. The bone count stays readable for the
            // snapshot below.
            matrices.reset();
            ctx.reset();
        }
        frameCaptureNanos += System.nanoTime() - captureStart;
        if (layers.isEmpty()) {
            // A renderer that submits nothing, as an empty shelf does, ends here.
            // Snapshotting the zero bones it left would swap the reusable buffer for
            // the shared empty array and force the next capture to allocate.
            return;
        }

        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        RemixEntityPass.EntityCaptureResult result = new RemixEntityPass.EntityCaptureResult(
                be,
                Vec3.atLowerCornerOf(be.getBlockPos()),
                0.0f,
                layers,
                reusableBoneData,
                ctx.getBoneCount(),
                typeKey(be),
                false);
        submit(be, result, submitter);
    }

    /**
     * Hands one block entity to the submitter. The submitter retires the owner's own
     * parts before a failure reaches here, so the block entity is absent this frame
     * and rebuilt on the next; the failure must not escape into vanilla's level render.
     */
    private void submit(BlockEntity be, RemixEntityPass.EntityCaptureResult result,
                        RemixEntitySubmitter submitter) {
        long submitStart = System.nanoTime();
        int partsBefore = submitter.getLastDrawCount();
        int meshCreatesBefore = submitter.getLastMeshCreateCount();
        try {
            submitter.submitEntity(result, STATIC_WORLD_CANVASES);
            frameCount++;
        } catch (Exception e) {
            failures.noteFailure("submitting", "block entity " + typeKey(be), be.getBlockPos(), e);
        } finally {
            frameSubmitNanos += System.nanoTime() - submitStart;
            frameParts += submitter.getLastDrawCount() - partsBefore;
            frameMeshCreates += submitter.getLastMeshCreateCount() - meshCreatesBefore;
        }
    }

    /**
     * Notes a block entity Entity Culling withheld from this frame's extraction, so that
     * {@link #keepWithheld} keeps its parts in the scene. A kept block entity shows the
     * pose it had when it was last extracted. See {@link #keepsWithheld} for which are
     * noted.
     */
    public void noteWithheld(BlockEntity be) {
        if (keepsWithheld(be.hasLevel(), be.isRemoved(), be instanceof CopperBeaconBlockEntity)) {
            withheld.add(be);
        }
    }

    /**
     * Whether a block entity Entity Culling withheld keeps its parts: it must still be in
     * its level, and its pose must not steer a light. A placed Copper Beacon's light
     * follows its live pose every frame, and a head held at an older angle would stand in
     * its own beam, so a withheld beacon retires like any block entity the frame did not
     * reach.
     */
    static boolean keepsWithheld(boolean hasLevel, boolean removed, boolean posesLight) {
        return hasLevel && !removed && !posesLight;
    }

    /**
     * Keeps the parts of the block entities noted this frame in the submitter's open
     * frame, then forgets them; see {@link RemixEntitySubmitter#keepOwner}. Called as the
     * entity frame closes, before the submitter's {@code endFrame} would retire them. One
     * never captured holds no parts, and one holding a part that cannot be kept retires;
     * neither counts.
     */
    public void keepWithheld(RemixEntitySubmitter submitter) {
        for (int i = 0, n = withheld.size(); i < n; i++) {
            if (submitter.keepOwner(withheld.get(i))) {
                frameCount++;
                frameCulled++;
            }
        }
        // Released at once: a world switch between frames would otherwise keep the
        // unloaded level reachable through them.
        withheld.clear();
    }

    /**
     * Reports and zeroes the frame's totals. Called once per frame from
     * {@code renderAndSubmitFrame}; retiring the block entities vanilla did not
     * submit belongs to the submitter's {@code endFrame}.
     */
    public FrameStats finishFrame() {
        failures.endFrame();
        FrameStats stats = new FrameStats(frameCount, frameCulled, frameParts, frameMeshCreates,
                (int) (frameCaptureNanos / 1000L), (int) (frameSubmitNanos / 1000L));
        frameCount = 0;
        frameCulled = 0;
        frameParts = 0;
        frameMeshCreates = 0;
        frameCaptureNanos = 0L;
        frameSubmitNanos = 0L;
        return stats;
    }

    private String typeKey(BlockEntity be) {
        return typeKeyCache.computeIfAbsent(be.getType(),
                type -> BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString());
    }
}
