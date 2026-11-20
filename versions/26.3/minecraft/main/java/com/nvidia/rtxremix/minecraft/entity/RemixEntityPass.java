/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.util.ClosestKHeap;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.ClientViewDistance;
import com.nvidia.rtxremix.minecraft.entity.ec.EntityCullingSupport;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Captures entity geometry for Remix and hands it to the submission layer.
 *
 * <p>Entities reach this class two ways. Those vanilla already draws are captured in place:
 * {@code LevelRendererEntityMixin} routes each render state vanilla is about to submit to
 * {@link #captureInPlace}, so the model is evaluated once rather than once for the
 * feature renderers and again here. A path tracer also needs entities the camera cannot
 * see, for shadows and reflections, so {@link #finishFrame} then sweeps the world for
 * entities vanilla skipped, extracts their render states itself and submits them
 * closest-first under a {@code MAX_ENTITIES} budget.
 *
 * <p>Both paths submit through the vanilla {@link EntityRenderDispatcher} into a
 * {@link RemixSubmitNodeCollector}, so Minecraft keeps ownership of animation, render
 * layers, multi-layer models and modded entities. Distant off-screen entities, and any that
 * Entity Culling culled, may replay a cached pose instead of re-running the dispatcher; the
 * world transform is still recomputed every frame, so a reused pose lags an animation but
 * never misplaces an entity. Entity Culling hands vanilla's traversal an empty state for an
 * entity no ray from the camera reaches, so such an entity is never captured in place and
 * reaches the closing sweep even inside the frustum.
 *
 * <p>Native Remix calls belong to the submitter, not to this class.
 */
public class RemixEntityPass {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixEntityPass.class);

    /**
     * Maximum entity candidates considered per frame, to avoid stalling.
     *
     * <p>The bound is on candidates rather than successful captures because the
     * cost being limited is the vanilla extract-and-submit of an entity, which a
     * candidate incurs whether or not it emits any vertices. Bounding successes
     * instead would let a crowd of geometry-less entities drive an unbounded
     * number of render calls per frame.
     *
     * <p>Consequently {@code Ent.Count} can sit below this cap in a saturated
     * frame, and {@code Ent.Count} plus {@code Ent.Skipped} need not equal the
     * in-range entity total: {@code Ent.Skipped} counts only in-range candidates
     * that lost the closest-first selection.
     */
    private static final int MAX_ENTITIES = 256;
    private static final int DEFAULT_ENTITY_CULL_CHUNKS = 7;
    private static final int DEFAULT_ENTITY_CULL_BLOCKS = DEFAULT_ENTITY_CULL_CHUNKS * 16;

    /**
     * Off-screen entities closer than this always get a freshly evaluated pose, unless
     * Entity Culling culled them. Near enough that their limbs can be a large part of a
     * reflection, so a few frames of stale animation would be visible.
     */
    private static final double POSE_REUSE_MIN_BLOCKS = 64.0;
    private static final double POSE_REUSE_MIN_SQ = POSE_REUSE_MIN_BLOCKS * POSE_REUSE_MIN_BLOCKS;

    static final int POSE_REFRESH_FRAMES = 4;
    /**
     * How long, in sweep frames, an entity Entity Culling culled replays one recorded pose.
     * No ray from the camera reaches it, so only its shadow and its reflections show it,
     * and replaying is what keeps Entity Culling's saving: the culled entity is not
     * extracted and evaluated again every frame. Its position is still taken fresh.
     */
    static final int CULLED_POSE_REFRESH_FRAMES = 120;

    private static final int TYPE_LOG_INTERVAL = 300;

    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final RemixSubmitNodeCollector collector = new RemixSubmitNodeCollector(provider);
    /**
     * The per-type breakdown costs a HashMap probe per entity per frame and a
     * stream sort plus a dozen String.formats on the render thread every summary,
     * a periodic hitch by construction. Diagnostics, so off unless asked for.
     */
    private static final boolean TYPE_SUMMARY_ENABLED =
            Boolean.getBoolean("rtxremix.debug.entityTypeSummary");
    private final Map<String, int[]> typeCounts = new HashMap<>();
    private final RecyclingMatrixStack sharedMatrices = new RecyclingMatrixStack();
    private final IdentityHashMap<EntityType<?>, String> entityTypeKeyCache = new IdentityHashMap<>();
    private final ClosestKHeap<Entity> entityHeap = new ClosestKHeap<>(MAX_ENTITIES);
    private final IntOpenHashSet capturedIds = new IntOpenHashSet(MAX_ENTITIES);
    private boolean frameActive = false;
    /** The placeholder block a landed falling block leaves; see {@link #captureTransientBlock}. */
    private final RemixTransientBlockPass transientBlockPass = new RemixTransientBlockPass();
    /** The submitter the open frame was begun with; what {@link #captureInPlace} submits to. */
    private RemixEntitySubmitter frameSubmitter;
    private int frameEntityCount, frameLayerCount, frameBoneCount, frameCapSkipped;
    // Split so the half we own in Java can be judged against the half that is
    // native, and so the off-frustum sweep can be judged against the entities
    // the camera can actually see.
    private long frameCaptureNanos, frameSubmitNanos, frameOffSweepNanos;
    private int frameOffCount, frameOffDraws, frameOffReused, frameCulled;
    private Vec3 firstPersonShadowModelOffset = Vec3.ZERO;
    // Deltas of the submitter's running totals taken around this pass's own submits,
    // so what the submitter's other owners draw in the same frame stays out of them.
    private int frameDraws, frameMeshCreates;

    /** One captured cuboid part, kept so a pose can be replayed without the model. */
    private static final class SnapshotPart {
        ModelPart.Polygon[] sides;
        TextureAtlasSprite sprite;
        int tint;
        int bone;
    }

    private static final class SnapshotLayer {
        RenderType renderLayer;
        int layerIndex;
        SnapshotPart[] parts = new SnapshotPart[0];
        int partCount;

        /** Room for {@code count} parts, keeping the part objects already made. */
        void resize(int count) {
            if (parts.length < count) {
                parts = Arrays.copyOf(parts, count);
            }
            for (int i = 0; i < count; i++) {
                if (parts[i] == null) {
                    parts[i] = new SnapshotPart();
                }
            }
            partCount = count;
        }
    }

    /**
     * Why an off-screen entity may replay a recorded pose instead of being captured afresh,
     * which also sets how often the pose is refreshed.
     */
    enum PoseReuse {
        /** Close and not culled: refreshed every frame, so never recorded or replayed. */
        NONE(1),
        /** Beyond {@link #POSE_REUSE_MIN_BLOCKS}, where a few frames of stale limbs do not show. */
        DISTANT(POSE_REFRESH_FRAMES),
        /** Culled by Entity Culling: no ray from the camera reaches it. */
        CULLED(CULLED_POSE_REFRESH_FRAMES);

        /** Sweep frames between refreshes of a replayed pose. */
        final int refreshFrames;

        PoseReuse(int refreshFrames) {
            this.refreshFrames = refreshFrames;
        }
    }

    /**
     * A captured animation pose, held so a distant or culled off-screen entity can be
     * resubmitted without re-evaluating its model. The world transform is NOT part
     * of it: that is recomputed from the entity every frame, so a reused pose never
     * puts the entity in the wrong place, it only lets its limbs lag. The model's
     * rotation is part of the pose, so a replayed entity also keeps the heading it
     * was recorded with.
     *
     * <p>Rewritten in place on each refresh: an entity's snapshot is read only on the
     * frames between its own refreshes, so the storage can be reused instead of
     * allocated for every distant entity every refresh.
     */
    private static final class PoseSnapshot {
        SnapshotLayer[] layers = new SnapshotLayer[0];
        int layerCount;
        float[] boneData = new float[0];
        int boneCount;
        String typeKey;
        /** The reuse the pose was recorded for; a replay continues only that one. */
        PoseReuse recordedFor;
        /**
         * Sweep frame the pose was last recorded or replayed in. A replay continues only a
         * pose used on the previous sweep frame, and eviction ages a pose by it.
         */
        long usedFrame;

        void resizeLayers(int count) {
            if (layers.length < count) {
                layers = Arrays.copyOf(layers, count);
            }
            for (int i = 0; i < count; i++) {
                if (layers[i] == null) {
                    layers[i] = new SnapshotLayer();
                }
            }
            layerCount = count;
        }
    }

    private final Int2ObjectOpenHashMap<PoseSnapshot> poseSnapshots = new Int2ObjectOpenHashMap<>();
    private final RemixVertexConsumerProvider replayProvider = new RemixVertexConsumerProvider();
    private ClientLevel snapshotWorld;
    private long sweepFrame;
    private float[] reusableBoneData = new float[0];
    private int typeLogFrames = 0;
    private long capSkippedSinceSummary = 0;
    private boolean capWarnActive = false;
    private long submitFailuresSinceSummary = 0;
    private boolean submitErrorActive = false;
    private volatile int entityCullBlocks = DEFAULT_ENTITY_CULL_BLOCKS;
    /**
     * Answers whether an entity id claimed a Remix light this frame. Read during the
     * closing sweep, which runs after the frame's light pass.
     */
    private final IntPredicate lightOwners;

    public RemixEntityPass(IntPredicate lightOwners) {
        this.lightOwners = lightOwners;
    }

    /**
     * Drops poses for entities that have not been submitted for a while. A culled entity
     * replays one pose for many frames, so a pose ages by its last use, not its recording.
     */
    private void evictStalePoseSnapshots() {
        if (poseSnapshots.size() <= MAX_ENTITIES) {
            return;
        }
        long oldest = sweepFrame - (long) POSE_REFRESH_FRAMES * 4L;
        poseSnapshots.values().removeIf(snapshot -> snapshot.usedFrame < oldest);
    }

    /**
     * Drops every recorded pose. A snapshot names the cuboids and sprites a model was
     * baked with, which a resource reload replaces, so a replay afterwards would rebuild
     * its meshes from the previous stitch's UV rectangles.
     */
    public void discardPoseSnapshots() {
        poseSnapshots.clear();
    }

    /** Abandons a capture window left open by a frame whose submit never ran. */
    public void resetFrame() {
        frameActive = false;
        frameSubmitter = null;
    }

    /**
     * Opens the per-frame capture window. Called from the start of vanilla's level
     * render, and again by {@link #finishFrame} so a frame that reaches no entity
     * still produces a well-formed scene. In-place captures submit to the submitter
     * given here, so the window must be open before vanilla submits its entities.
     */
    public void beginFrame(RemixEntitySubmitter submitter) {
        if (frameActive) {
            return;
        }
        frameActive = true;
        frameSubmitter = submitter;
        capturedIds.clear();
        frameEntityCount = 0;
        frameLayerCount = 0;
        frameBoneCount = 0;
        frameCapSkipped = 0;
        frameCaptureNanos = 0L;
        frameSubmitNanos = 0L;
        frameOffSweepNanos = 0L;
        frameOffCount = 0;
        frameOffDraws = 0;
        frameOffReused = 0;
        frameCulled = 0;
        frameDraws = 0;
        frameMeshCreates = 0;
        transientBlockPass.beginFrame();
        submitter.beginFrame();
    }

    /**
     * Captures the placeholder block vanilla submits for a moment after a falling block
     * lands, at vanilla's own submit point, in place of that submit. It continues the
     * falling block entity this pass captured until the entity was removed, so it follows
     * the entity toggle: returning false, because no frame is open, hands it back to
     * vanilla. See {@link RemixTransientBlockPass}.
     */
    public boolean captureTransientBlock(MovingBlockRenderState state) {
        if (!frameActive || frameSubmitter == null || EntityRenderContext.get().isActive()) {
            return false;
        }
        transientBlockPass.capture(state, frameSubmitter);
        return true;
    }

    /**
     * Captures one entity at vanilla's own submit point, with the render state vanilla
     * already extracted for it, in place of the submit that would have gone to the
     * feature renderers.
     *
     * <p>Returning true tells the caller to skip vanilla's submit, which is what reduces
     * each entity to a single model evaluation per frame. Returning false, because no
     * frame is open, hands the entity back to vanilla, so a renderer that never opened
     * the frame still renders normally.
     */
    public boolean captureInPlace(Entity entity, EntityRenderState state, CameraRenderState camera,
                                  EntityRenderDispatcher dispatcher) {
        if (!frameActive || frameSubmitter == null) {
            return false;
        }
        captureAndSubmit(entity, state, camera, dispatcher, frameSubmitter, PoseReuse.NONE);
        return true;
    }

    /**
     * Captures one entity vanilla did not extract this frame, extracting its render
     * state here, and submits it immediately.
     *
     * @return true if this frame claimed the entity, false if it was already captured
     */
    public boolean captureAndSubmit(Entity entity, EntityRenderDispatcher dispatcher,
                                    float tickDelta, RemixEntitySubmitter submitter) {
        return captureAndSubmit(entity, dispatcher, tickDelta, submitter, PoseReuse.NONE);
    }

    private boolean captureAndSubmit(Entity entity, EntityRenderDispatcher dispatcher,
                                     float tickDelta, RemixEntitySubmitter submitter,
                                     PoseReuse reuse) {
        if (capturedIds.contains(entity.getId())) {
            return false;
        }
        long extractStart = System.nanoTime();
        EntityRenderState state;
        try {
            state = dispatcher.extractEntity(entity, tickDelta);
        } catch (Exception e) {
            // Claimed all the same: a state that cannot be extracted will not extract
            // on a retry this frame either.
            capturedIds.add(entity.getId());
            LOGGER.error("Error extracting entity {}: {}", entity.getType(), e.getMessage());
            return true;
        }
        frameCaptureNanos += System.nanoTime() - extractStart;
        return captureAndSubmit(entity, state, cameraState(), dispatcher, submitter, reuse);
    }

    /**
     * Captures one entity through vanilla's dispatcher and submits it immediately.
     *
     * <p>Submitting before the next entity renders is what lets the consumer pool
     * recycle: a layer's data is consumed before anything can overwrite it. Unless
     * {@code reuse} is {@link PoseReuse#NONE}, the pose is recorded for that reuse.
     *
     * @return true if this frame claimed the entity, false if it was already captured
     */
    private boolean captureAndSubmit(Entity entity, EntityRenderState state, CameraRenderState camera,
                                     EntityRenderDispatcher dispatcher, RemixEntitySubmitter submitter,
                                     PoseReuse reuse) {
        if (!capturedIds.add(entity.getId())) {
            return false;
        }
        long captureStart = System.nanoTime();
        EntityCaptureResult result;
        try {
            result = renderOneEntity(entity, state, camera, dispatcher,
                    isFirstPersonLocalPlayer(Minecraft.getInstance(), entity));
        } catch (Exception e) {
            LOGGER.error("Error capturing entity {}: {}", entity.getType(), e.getMessage());
            return true;
        }
        long captured = System.nanoTime();
        frameCaptureNanos += captured - captureStart;
        if (result == null) {
            return true;
        }
        if (!submitToScene(entity, result, submitter)) {
            return true;
        }
        if (reuse != PoseReuse.NONE && snapshotPose(entity, result, reuse) && reuse == PoseReuse.CULLED) {
            frameCulled++;
        }
        frameEntityCount++;
        frameLayerCount += result.layers().size();
        frameBoneCount += result.boneCount();
        if (TYPE_SUMMARY_ENABLED) {
            int[] typeStats = typeCounts.computeIfAbsent(result.entityTypeKey(), k -> new int[3]);
            typeStats[0]++;
            typeStats[1] += result.layers().size();
            typeStats[2] += result.boneCount();
        }
        return true;
    }

    /**
     * Hands one entity to the submitter and keeps a failure to that entity. The
     * in-place path runs inside vanilla's LevelRenderer.render, where an escaping
     * native failure would end the frame and crash the client; on the off-frustum
     * sweep it would abandon every entity behind this one and retire their instances
     * at endFrame. The submitter retires the entity's own retained parts before the
     * failure reaches here, so the entity is absent this frame and rebuilt on the next.
     *
     * @return true if the entity reached the scene
     */
    private boolean submitToScene(Entity entity, EntityCaptureResult result,
                                  RemixEntitySubmitter submitter) {
        long submitStart = System.nanoTime();
        int drawsBefore = submitter.getLastDrawCount();
        int meshCreatesBefore = submitter.getLastMeshCreateCount();
        try {
            Vec3 renderOffset = result.firstPersonLocalPlayer()
                    ? firstPersonShadowModelOffset
                    : Vec3.ZERO;
            submitter.submitEntity(result, renderOffset);
            return true;
        } catch (Exception e) {
            noteSubmitFailure(entity, e);
            return false;
        } finally {
            frameSubmitNanos += System.nanoTime() - submitStart;
            frameDraws += submitter.getLastDrawCount() - drawsBefore;
            frameMeshCreates += submitter.getLastMeshCreateCount() - meshCreatesBefore;
        }
    }

    /**
     * Logs the first submit failure of an episode. The pass continues past a
     * failure, so a persistent one would otherwise print a line per entity per
     * frame; later failures are counted and the log re-arms after a clean
     * {@link #TYPE_LOG_INTERVAL} window.
     */
    private void noteSubmitFailure(Entity entity, Exception e) {
        submitFailuresSinceSummary++;
        if (submitErrorActive) {
            return;
        }
        submitErrorActive = true;
        LOGGER.error("Error submitting entity {}: {} (further submit failures are counted, "
                + "not logged, until a {}-frame window passes without one)",
                entity.getType(), e.getMessage(), TYPE_LOG_INTERVAL);
    }

    /**
     * Totals for one frame's entity pass. Counts cover both in-place and off-frustum
     * captures; the off-frustum fields are the subset from the closing sweep, and
     * {@code culled} is the part of that sweep Entity Culling culled that a replayed pose,
     * or one recorded this frame, kept in the scene. Draw and mesh-create counts are this
     * pass's own submits, not the submitter's frame totals, which also carry its other
     * owners. Times are microseconds, sized for the counters they feed.
     */
    public record FrameStats(int entityCount, int layerCount, int boneCount,
                             int drawCount, int meshCreates, int capSkipped,
                             int captureMicros, int submitMicros,
                             int offFrustumCount, int offSweepMicros,
                             int offFrustumDraws, int offFrustumReused,
                             int culled, int poolSize) {}

    /**
     * Closes the frame: sweeps up the nearby entities vanilla's frustum skipped,
     * then reports the frame totals. Vanilla omits the local player from its
     * first-person traversal, so the closing sweep captures that hidden shadow
     * model after light placement has resolved its current render-only offset.
     */
    public FrameStats finishFrame(ClientLevel world, Vec3 cameraPos, float tickDelta,
                                  Vec3 firstPersonShadowModelOffset,
                                  RemixEntitySubmitter submitter) {
        this.firstPersonShadowModelOffset = firstPersonShadowModelOffset;
        beginFrame(submitter);
        Minecraft client = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = client == null ? null : client.getEntityRenderDispatcher();
        // Entity ids are per world, so a stale snapshot would alias a different
        // entity after a world change.
        if (world != snapshotWorld) {
            snapshotWorld = world;
            poseSnapshots.clear();
        }
        sweepFrame++;
        if (dispatcher != null) {
            try (ProfileScope offScreenScope = ProfileScope.begin("Entities offscreen")) {
                captureOffFrustum(world, cameraPos, tickDelta, dispatcher, submitter);
            }
        }
        evictStalePoseSnapshots();

        if (++typeLogFrames >= TYPE_LOG_INTERVAL) {
            // The saturation warn and the submit error re-arm on a clean window whether
            // or not the breakdown itself is enabled; they are the operational signals.
            if (capSkippedSinceSummary == 0) {
                capWarnActive = false;
            }
            if (submitFailuresSinceSummary == 0) {
                submitErrorActive = false;
            }
            if (TYPE_SUMMARY_ENABLED) {
                logEntityTypeSummary();
                typeCounts.clear();
            }
            capSkippedSinceSummary = 0;
            submitFailuresSinceSummary = 0;
            typeLogFrames = 0;
        }

        frameActive = false;
        frameSubmitter = null;
        return new FrameStats(frameEntityCount, frameLayerCount, frameBoneCount,
                frameDraws, frameMeshCreates,
                frameCapSkipped,
                (int) (frameCaptureNanos / 1000L), (int) (frameSubmitNanos / 1000L),
                frameOffCount, (int) (frameOffSweepNanos / 1000L), frameOffDraws,
                frameOffReused, frameCulled,
                submitter.getPoolSize());
    }

    /**
     * Captures in-range entities that vanilla's frustum-culled extraction skipped.
     * They are invisible to the raster pass but still cast shadows and appear in
     * reflections, so a path tracer needs them.
     *
     * <p>Selection is closest-first, so a newly spawned nearby entity is never
     * starved by older distant ones that happen to iterate first. The budget is
     * whatever remains of {@link #MAX_ENTITIES} after the in-place captures.
     *
     * <p>An entity that owns a Remix light this frame is admitted at any distance
     * and never evicted: its light already reaches the scene from wherever it
     * stands, and a beam without its housing looks broken.
     *
     * <p>An entity Entity Culling culled is admitted past the sweep's own range as far
     * as vanilla itself would render it, at its distance's place in the closest-first
     * order. Without Entity Culling vanilla would extract such an entity, and the
     * capture take it in place; with it, the sweep is its only way into the scene.
     */
    private void captureOffFrustum(ClientLevel world, Vec3 cameraPos, float tickDelta,
                                   EntityRenderDispatcher dispatcher, RemixEntitySubmitter submitter) {
        int budget = MAX_ENTITIES - capturedIds.size();
        if (budget <= 0) {
            return;
        }
        // Bounded to the budget, not to MAX_ENTITIES. Heap order is not distance order, so
        // keeping the first `budget` slots of a larger heap would keep the farthest entity
        // (slot 0 is the max) and drop near ones from the leaves. Letting the heap do the
        // eviction is what actually makes the selection closest-first.
        entityHeap.reset(budget);
        // Timed from here so the world walk and heap build below fall inside the section
        // that reports the off-frustum sweep.
        long sweepStart = System.nanoTime();
        long captureNanosBefore = frameCaptureNanos;
        long submitNanosBefore = frameSubmitNanos;
        double maxDistSq = getEffectiveEntityRenderDistance();
        maxDistSq *= maxDistSq;
        for (Entity entity : world.entitiesForRendering()) {
            if (capturedIds.contains(entity.getId())) {
                continue;
            }
            double distSq = entity.distanceToSqr(cameraPos);
            boolean ownsLight = lightOwners.test(entity.getId());
            if (!sweepAdmits(distSq, maxDistSq, ownsLight)) {
                // Asked only past the sweep's range, so the common path pays nothing for
                // it, and vanilla's distance check only of an entity Entity Culling culled.
                boolean culled = EntityCullingSupport.isCulled(entity);
                if (!sweepAdmitsPastRange(culled,
                        culled && entity.shouldRender(cameraPos.x, cameraPos.y, cameraPos.z))) {
                    continue;
                }
            }
            entityHeap.offer(entity, sweepPriority(distSq, ownsLight));
        }
        int drawsBefore = frameDraws;
        try {
            int skipped = entityHeap.overflowCount();
            if (skipped > 0) {
                frameCapSkipped = skipped;
                capSkippedSinceSummary += skipped;
                if (!capWarnActive) {
                    capWarnActive = true;
                    LOGGER.warn("Entity capture cap ({}) reached: {} in-range entities not captured "
                            + "this frame (capturing closest first; see Ent.Skipped counter)",
                            MAX_ENTITIES, skipped);
                }
            }
            for (int i = 0, n = entityHeap.size(); i < n; i++) {
                Entity entity = entityHeap.get(i);
                if (submitOffFrustum(entity, cameraPos, tickDelta, dispatcher, submitter)) {
                    frameOffCount++;
                }
            }
            frameOffDraws = frameDraws - drawsBefore;
            // Capture and submit inject as flat sibling sections, so the sweep excludes the
            // capture and submit time it contains and the three sum to the pass.
            frameOffSweepNanos = (System.nanoTime() - sweepStart)
                    - (frameCaptureNanos - captureNanosBefore)
                    - (frameSubmitNanos - submitNanosBefore);
        } finally {
            // Release the references immediately: a world switch between frames
            // would otherwise keep the unloaded ClientWorld reachable through them.
            entityHeap.clear();
        }
    }

    /**
     * Whether the closing sweep considers an entity at all. Distance culls everything
     * except an entity that owns a Remix light this frame.
     */
    static boolean sweepAdmits(double distSq, double maxDistSq, boolean ownsLight) {
        return ownsLight || distSq <= maxDistSq;
    }

    /**
     * Whether the closing sweep considers an entity past its own range after all: Entity
     * Culling culled it, and vanilla would render it from the camera, judging by its own
     * render distance for the entity's size.
     */
    static boolean sweepAdmitsPastRange(boolean culled, boolean vanillaRenders) {
        return culled && vanillaRenders;
    }

    /**
     * Heap priority of an admitted entity; lower is kept first. A non-owner ranks by
     * squared distance. A light owner ranks below every non-owner so a saturated
     * {@link #MAX_ENTITIES} budget cannot evict it, and owners keep distance order among
     * themselves so the nearer one wins when two compete for the last slot.
     */
    static double sweepPriority(double distSq, boolean ownsLight) {
        return ownsLight ? -1.0 / (1.0 + distSq) : distSq;
    }

    private void logEntityTypeSummary() {
        if (typeCounts.isEmpty() && capSkippedSinceSummary == 0 && submitFailuresSinceSummary == 0) return;
        int frames = typeLogFrames > 0 ? typeLogFrames : 1;
        StringBuilder sb = new StringBuilder("\n=== Entity Type Breakdown (")
                .append(frames).append(" frames) ===\n");
        sb.append(String.format("  %-35s %8s %8s %8s\n", "Type", "Ent/frm", "Lay/frm", "Bone/frm"));
        typeCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> {
                    int[] s = e.getValue();
                    sb.append(String.format("  %-35s %8.1f %8.1f %8.1f\n",
                            e.getKey(),
                            s[0] / (double) frames,
                            s[1] / (double) frames,
                            s[2] / (double) frames));
                });
        if (capSkippedSinceSummary > 0) {
            sb.append(String.format("  capture cap (%d) skipped: %.1f entities/frame\n",
                    MAX_ENTITIES, capSkippedSinceSummary / (double) frames));
        }
        if (submitFailuresSinceSummary > 0) {
            sb.append(String.format("  submit failures: %.1f entities/frame\n",
                    submitFailuresSinceSummary / (double) frames));
        }
        sb.append("==========================================");
        LOGGER.info(sb.toString());
    }

    /**
     * Sets the maximum entity capture distance in chunks. 0 = use Minecraft's
     * view distance.
     *
     * <p>Chunks at this surface so it reads the same as every other distance
     * setting and as Minecraft's own render distance. The comparison itself is
     * genuinely per-entity and continuous, so blocks stay the internal unit.
     */
    public void setEntityCullDistance(int chunks) {
        entityCullBlocks = Math.max(0, chunks) * 16;
    }

    /** Returns the entity capture distance in chunks. */
    public int getEntityCullDistance() {
        return entityCullBlocks / 16;
    }

    /**
     * Submits one off-frustum entity, reusing its recorded pose when it may and the
     * pose is not due a refresh this frame.
     *
     * <p>A close entity Entity Culling did not cull is evaluated every frame: its limbs
     * can be a large part of a reflection. Beyond that, a pose is reused for at most
     * {@link #POSE_REFRESH_FRAMES} frames. An entity Entity Culling culled reuses one at
     * whatever distance the sweep admits it, for up to {@link #CULLED_POSE_REFRESH_FRAMES}
     * frames, and its first culled frame is a full capture that records it. The entity's
     * position is recomputed every frame regardless, so what lags is the animation and
     * never the place. The local player's hidden first-person body is never replayed: a
     * replay would lose the categories that set it apart from other players.
     */
    private boolean submitOffFrustum(Entity entity, Vec3 cameraPos, float tickDelta,
                                     EntityRenderDispatcher dispatcher,
                                     RemixEntitySubmitter submitter) {
        boolean culled = EntityCullingSupport.isCulled(entity)
                && !isFirstPersonLocalPlayer(Minecraft.getInstance(), entity);
        PoseReuse reuse = poseReuse(culled, entity.distanceToSqr(cameraPos));
        if (reuse != PoseReuse.NONE) {
            PoseSnapshot snapshot = poseSnapshots.get(entity.getId());
            if (snapshot != null
                    && replaysPose(reuse, snapshot.recordedFor, snapshot.usedFrame,
                            entity.getId(), sweepFrame)
                    && capturedIds.add(entity.getId())) {
                if (replayPose(entity, snapshot, tickDelta, submitter)) {
                    return true;
                }
                // Replay failed, so fall through to a full capture. The id is already
                // claimed, so clear it or captureAndSubmit will treat it as a duplicate.
                capturedIds.remove(entity.getId());
            }
        }
        return captureAndSubmit(entity, dispatcher, tickDelta, submitter, reuse);
    }

    /**
     * Which reuse of a recorded pose an off-screen entity is open to: one Entity Culling
     * culled, which no ray from the camera reaches, at any distance; any other only beyond
     * {@link #POSE_REUSE_MIN_BLOCKS}.
     */
    static PoseReuse poseReuse(boolean culled, double distSq) {
        if (culled) {
            return PoseReuse.CULLED;
        }
        return distSq > POSE_REUSE_MIN_SQ ? PoseReuse.DISTANT : PoseReuse.NONE;
    }

    /**
     * Whether an off-screen entity replays its recorded pose this sweep frame rather than
     * being captured afresh.
     *
     * <p>A pose replays only within the run that recorded it: consecutive sweep frames
     * that reached the entity for one reuse. An entity the sweep missed for a frame,
     * because vanilla drew it on screen and the capture took it in place, or the budget
     * left it out, may have moved and turned since its pose, and the reuses refresh at
     * different rates, so every run starts with a full capture that records the pose
     * afresh. Within a run the pose is refreshed every {@link PoseReuse#refreshFrames}
     * sweep frames, on frames staggered by entity id so that many entities spread their
     * refreshes; that stagger is what bounds the age of a replayed pose.
     *
     * @param recordedFor the reuse the pose was recorded for
     * @param usedFrame   sweep frame the pose was last recorded or replayed in
     */
    static boolean replaysPose(PoseReuse reuse, PoseReuse recordedFor, long usedFrame,
                               int entityId, long sweepFrame) {
        return reuse == recordedFor
                && usedFrame >= sweepFrame - 1
                && Math.floorMod(entityId + sweepFrame, reuse.refreshFrames) != 0;
    }

    /**
     * Records a pose so later frames can resubmit this entity without evaluating
     * its model again.
     *
     * <p>Only cuboid-backed geometry can be replayed: a cuboid part names the
     * immutable {@code ModelPart.Cube} it came from, so it can be rebuilt at any
     * time, while a baked item or map part exists only as captured vertices in a
     * pooled consumer that is recycled the moment the next entity renders. An
     * entity carrying any of the latter is skipped rather than half-snapshotted.
     *
     * @return whether the pose was recorded
     */
    private boolean snapshotPose(Entity entity, EntityCaptureResult result, PoseReuse reuse) {
        List<RemixVertexConsumerProvider.CapturedLayer> layers = result.layers();
        // Check replayability before touching the stored pose, so a failed refresh leaves
        // no half-written snapshot behind.
        for (RemixVertexConsumerProvider.CapturedLayer layer : layers) {
            RemixCapturingConsumer consumer = layer.consumer();
            for (int p = 0, n = consumer.getPartCount(); p < n; p++) {
                if (consumer.getPartSides(p) == null) {
                    // An entity can stop being snapshottable, by picking up an item for
                    // instance. Any pose recorded before that is now unreachable geometry, and
                    // leaving it would have refresh frames capture the new pose while the
                    // frames between them replayed the old one.
                    poseSnapshots.remove(entity.getId());
                    return false;
                }
            }
        }

        PoseSnapshot snapshot = poseSnapshots.get(entity.getId());
        if (snapshot == null) {
            snapshot = new PoseSnapshot();
            poseSnapshots.put(entity.getId(), snapshot);
        }
        snapshot.resizeLayers(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            RemixVertexConsumerProvider.CapturedLayer layer = layers.get(i);
            RemixCapturingConsumer consumer = layer.consumer();
            SnapshotLayer snapLayer = snapshot.layers[i];
            snapLayer.renderLayer = layer.renderLayer();
            snapLayer.layerIndex = layer.layerIndex();
            int partCount = consumer.getPartCount();
            snapLayer.resize(partCount);
            for (int p = 0; p < partCount; p++) {
                SnapshotPart part = snapLayer.parts[p];
                part.sides = consumer.getPartSides(p);
                part.sprite = consumer.getPartSprite(p);
                part.tint = consumer.getPartTint(p);
                part.bone = consumer.getPartBone(p);
            }
        }
        // The pass reuses one bone array across entities, so this has to be a copy.
        int boneFloats = result.boneCount() * 12;
        if (snapshot.boneData.length < boneFloats) {
            snapshot.boneData = new float[boneFloats];
        }
        System.arraycopy(result.boneData(), 0, snapshot.boneData, 0, boneFloats);
        snapshot.boneCount = result.boneCount();
        snapshot.typeKey = result.entityTypeKey();
        snapshot.recordedFor = reuse;
        snapshot.usedFrame = sweepFrame;
        return true;
    }

    /**
     * Resubmits an entity from a recorded pose, with its world transform taken
     * fresh so it stays exactly where it is.
     *
     * @return false if the pose could not be replayed, in which case the caller
     *         must fall back to a full capture; true once the entity is handled,
     *         whether or not its submit reached the scene
     */
    private boolean replayPose(Entity entity, PoseSnapshot snapshot, float tickDelta,
                               RemixEntitySubmitter submitter) {
        replayProvider.beginEntity();
        for (int i = 0; i < snapshot.layerCount; i++) {
            SnapshotLayer layer = snapshot.layers[i];
            VertexConsumer buffer = replayProvider.getBuffer(layer.renderLayer);
            if (!(buffer instanceof RemixCapturingConsumer consumer)) {
                replayProvider.endEntity();
                return false;
            }
            // Keep the ordinal the layer was first given: it scales the shell offset
            // baked into the mesh, so replaying under a different one would key a
            // different mesh and separate the layer from the body it belongs to.
            consumer.setLayerIndex(layer.layerIndex);
            for (int p = 0; p < layer.partCount; p++) {
                SnapshotPart part = layer.parts[p];
                consumer.replayCuboidPart(part.sides, part.tint, part.bone, part.sprite);
            }
        }

        List<RemixVertexConsumerProvider.CapturedLayer> layers = replayProvider.endEntity();
        if (layers.isEmpty()) {
            return false;
        }
        snapshot.usedFrame = sweepFrame;
        EntityCaptureResult result = new EntityCaptureResult(
                entity,
                entity.getPosition(tickDelta),
                entity.getYRot(tickDelta),
                layers,
                snapshot.boneData,
                snapshot.boneCount,
                snapshot.typeKey,
                false);
        if (!submitToScene(entity, result, submitter)) {
            // The pose replayed and the scene rejected it. A full capture would only
            // resubmit the same geometry, so the entity stays claimed and absent.
            return true;
        }

        // A replayed entity is in the scene exactly as a captured one is, so it has
        // to count the same. Only the capture time differs, which is the point.
        frameEntityCount++;
        frameOffReused++;
        if (snapshot.recordedFor == PoseReuse.CULLED) {
            frameCulled++;
        }
        frameLayerCount += layers.size();
        frameBoneCount += snapshot.boneCount;
        if (TYPE_SUMMARY_ENABLED) {
            int[] typeStats = typeCounts.computeIfAbsent(snapshot.typeKey, k -> new int[3]);
            typeStats[0]++;
            typeStats[1] += layers.size();
            typeStats[2] += snapshot.boneCount;
        }
        return true;
    }

    private double getEffectiveEntityRenderDistance() {
        double minecraftDistance = ClientViewDistance.blocks();
        int cap = entityCullBlocks;
        return cap > 0 ? Math.min(minecraftDistance, cap) : minecraftDistance;
    }

    /** The frame's camera render state, which vanilla extracted before the level render. */
    private static CameraRenderState cameraState() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
    }

    /**
     * Submits a single entity's render state through vanilla's dispatcher and captures
     * the result.
     *
     * <p>The entity is submitted at the origin so the captured rigid transforms
     * carry only model and pivot rotations. The world position, the interpolated
     * one vanilla extracted into the state, rides on the Remix instance transform
     * instead, so it is applied exactly once.
     */
    private EntityCaptureResult renderOneEntity(Entity entity,
                                                 EntityRenderState state,
                                                 CameraRenderState camera,
                                                 EntityRenderDispatcher dispatcher,
                                                 boolean firstPersonLocalPlayer) {
        Vec3 entityPos = new Vec3(state.x, state.y, state.z);

        provider.beginEntity();
        EntityRenderContext ctx = EntityRenderContext.get();

        sharedMatrices.pushPose();
        try {
            dispatcher.submit(state, camera, 0.0, 0.0, 0.0, sharedMatrices, collector);
        } finally {
            // Both reset even if a renderer threw mid-traversal, which skips the
            // dispatcher's RETURN hook: the identity root returns the stack to its
            // root, and a capture left open would make the next one look nested
            // and keep its bone numbering. The bone count stays readable for the
            // snapshot below.
            sharedMatrices.reset();
            ctx.reset();
        }

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();

        if (layers.isEmpty()) {
            return null;
        }

        // One reusable buffer suffices: submission consumes the bone data before
        // the next entity renders over it.
        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        float[] boneData = reusableBoneData;
        int boneCount = ctx.getBoneCount();

        String typeKey = entityTypeKeyCache.computeIfAbsent(entity.getType(),
                t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString());

        return new EntityCaptureResult(
            entity,
            entityPos,
            entity.getYRot(),
            layers,
            boneData,
            boneCount,
            typeKey,
            firstPersonLocalPlayer
        );
    }

    private static boolean isFirstPersonLocalPlayer(Minecraft client, Entity entity) {
        return entity == client.player && client.options.getCameraType().isFirstPerson();
    }

    // ---- Result container ----

    /**
     * The captured result of rendering one owner's model through vanilla's pipeline:
     * everything the submitter needs to build rigid meshes and retained instances.
     *
     * @param owner     the game object whose parts these are. Retained instances are
     *                  keyed on it by identity, so it is the object itself rather than
     *                  a numeric ID the game may recycle.
     * @param worldPos  the owner's world position, from which the world-position
     *                  submit overloads build the root the captured pivot transforms
     *                  compose onto. A caller that supplies its own root passes
     *                  {@link Vec3#ZERO}.
     * @param yaw       the owner's yaw at capture, in degrees; informational
     * @param layers    captured vertices grouped by RenderType and texture
     * @param boneData  flat float array: 12 floats per bone (3 rows x 4 cols).
     *                  Layout per bone matches Transform's set(row, col) order.
     * @param boneCount number of bones (boneData.length / 12)
     * @param entityTypeKey the owner's registry id, or a label the capturing pass
     *                  chooses, under which per-type statistics are grouped
     * @param firstPersonLocalPlayer whether these are the local player's own body
     *                  parts while the camera is in first person; they carry the
     *                  third-person player categories so Remix can treat the body
     *                  the camera sits inside differently from other players
     */
    public record EntityCaptureResult(
        Object owner,
        Vec3 worldPos,
        float yaw,
        List<RemixVertexConsumerProvider.CapturedLayer> layers,
        float[] boneData,
        int boneCount,
        String entityTypeKey,
        boolean firstPersonLocalPlayer
    ) { }
}
