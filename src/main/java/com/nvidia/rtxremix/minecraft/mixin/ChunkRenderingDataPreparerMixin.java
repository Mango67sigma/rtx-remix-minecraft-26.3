/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.TimeSlicedWorkQueue;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.FarChunkProbe;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkInfoPool;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.google.common.collect.Queues;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.Node;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Takes over parts of vanilla's section occlusion graph ({@code SectionOcclusionGraph}): it
 * runs full visibility walks on a dedicated low-priority executor so long view distances
 * cannot starve terrain capture or the render thread, it replaces the walk itself with an
 * allocation-free port that produces vanilla's graph exactly, and it time-slices vanilla's
 * incremental propagation on the render thread through a per-frame budget so a chunk-load
 * burst cannot stall a frame.
 *
 * <p>It also labels each phase for the profiler.
 *
 * <p>Minecraft 26.3 notes. Since 1.21.2 the graph keeps the reached sections in an
 * {@code Octree} whose {@code visitNodes} already culls whole octants against the frustum, so
 * {@code addSectionsInFrustum} is left to vanilla: the coarse {@code BuiltChunkFrustumIndex}
 * that the 1.21.1 port built over vanilla's flat reached-section list has no work left to do.
 * The full walk is the lambda {@code lambda$scheduleFullUpdate$0} (1.21.1's
 * {@code method_52820}), submitted through {@code CompletableFuture.runAsync}.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class ChunkRenderingDataPreparerMixin {
    /** Vanilla's neighbour order; the walk must visit directions in this order. */
    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    /** Ray-march step of vanilla's far-section probe, {@code ceil(sqrt(3) * 16)} blocks. */
    @Shadow
    @Final
    private static double CEILED_SECTION_DIAGONAL;

    /** Section distance beyond which vanilla runs the far-section probe, {@code 60 >> 4}. */
    @Shadow
    @Final
    private static int MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE;

    @Shadow
    private ViewArea viewArea;

    /** Vanilla's neighbour lookup: null beyond the view distance or the vertical view distance. */
    @Shadow
    protected abstract SectionRenderDispatcher.RenderSection getRelativeFrom(
            long cameraSectionNode, SectionRenderDispatcher.RenderSection section, Direction direction);

    /**
     * {@code -Drtxremix.vanillaVisibilityWalk=true} leaves the visibility walk to vanilla with
     * RTX on. The port below must produce vanilla's graph exactly; this is how to check that on
     * a live world, together with {@code -Drtxremix.logVisibleSet=true}, which logs the size and
     * a hash of every walk's graph whenever it changes. Run the same camera with and without
     * the switch and compare the last line each logged.
     */
    private static final boolean rtxremix$VANILLA_WALK = Boolean.getBoolean("rtxremix.vanillaVisibilityWalk");
    private static final boolean rtxremix$LOG_VISIBLE_SET = Boolean.getBoolean("rtxremix.logVisibleSet");
    private static long rtxremix$lastVisibleSetSignature;
    private static final ExecutorService rtxremix$visibilityExecutor =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "RTX Remix chunk visibility");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });

    /**
     * Per-frame budget for vanilla's incremental visibility propagation. After a chunk-load
     * burst the propagation can seed a walk of thousands of sections; unbudgeted, one such
     * walk cost 25 ms on the render thread. The poll floor keeps the fringe advancing even
     * when the frame is already late.
     */
    private static final long rtxremix$PROPAGATION_BUDGET_NANOS = 1_500_000L;
    private static final int rtxremix$PROPAGATION_MINIMUM_POLLS = 64;

    /**
     * Seeds and partially walked sections carried across frames. Its entries belong to the
     * visibility state that produced them, so a publish or a storage swap empties it.
     */
    private TimeSlicedWorkQueue<Object> rtxremix$propagationQueue;
    /** Seed queue of the state whose entries {@link #rtxremix$propagationQueue} holds. */
    private BlockingQueue<?> rtxremix$propagationSeeds;

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$storageScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$frustumScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$prepareScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$graphScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$seedScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$walkScope = new ThreadLocal<>();

    /**
     * Created here rather than by a field initialiser: on 26.3 the initialiser, whose
     * {@code System::nanoTime} method reference compiles to an {@code invokedynamic}, was not
     * carried into the target constructor and left the field null at the first
     * {@code waitAndReset}. A constructor hook runs for the single constructor unconditionally.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$createPropagationQueue(CallbackInfo ci) {
        rtxremix$propagationQueue = new TimeSlicedWorkQueue<>(
                rtxremix$PROPAGATION_BUDGET_NANOS, rtxremix$PROPAGATION_MINIMUM_POLLS, System::nanoTime);
    }

    @Inject(method = "waitAndReset", at = @At("HEAD"))
    private void rtxremix$storageHead(ViewArea storage, CallbackInfo ci) {
        rtxremix$open(rtxremix$storageScope, "ChunkVisibility.setStorage");
        // The initial empty state vanilla builds below takes the current buffers; size them
        // for the new storage first.
        VisibilityGraphBuffers.configure(storage != null ? storage.size() : 0);
    }

    @Inject(method = "waitAndReset", at = @At("RETURN"))
    private void rtxremix$storageReturn(ViewArea storage, CallbackInfo ci) {
        rtxremix$propagationQueue.clear();
        rtxremix$propagationSeeds = null;
        rtxremix$close(rtxremix$storageScope);
    }

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"))
    private void rtxremix$frustumHead(
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> visibleSections,
            List<SectionRenderDispatcher.RenderSection> nearbyVisibleSections,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$frustumScope, "ChunkVisibility.applyFrustum", rtxremix$sizeOf(visibleSections));
    }

    @Inject(method = "addSectionsInFrustum", at = @At("RETURN"))
    private void rtxremix$frustumReturn(
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> visibleSections,
            List<SectionRenderDispatcher.RenderSection> nearbyVisibleSections,
            CallbackInfo ci) {
        rtxremix$captureCount(rtxremix$frustumScope, rtxremix$sizeOf(visibleSections));
        rtxremix$close(rtxremix$frustumScope);
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void rtxremix$prepareHead(
            CameraRenderState camera,
            int fov,
            ChunkLoadingRenderState chunkLoadingRenderState,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$prepareScope, "ChunkVisibility.prepare");
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void rtxremix$prepareReturn(
            CameraRenderState camera,
            int fov,
            ChunkLoadingRenderState chunkLoadingRenderState,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$prepareScope);
    }

    /** The full walk runs on the low-priority visibility thread instead of the shared pool. */
    @ModifyArg(
            method = "scheduleFullUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            ),
            index = 1)
    private Executor rtxremix$visibilityExecutor(Executor vanillaExecutor) {
        return RtxRemixMod.isRtxEnabled() ? rtxremix$visibilityExecutor : vanillaExecutor;
    }

    /**
     * A full walk is about to construct its state: hand it the other buffer set. Runs for
     * vanilla's walk too, since the reused node map and pool are what its state is made of.
     */
    @Inject(method = "lambda$scheduleFullUpdate$0", at = @At("HEAD"))
    private void rtxremix$beginFullWalk(
            CameraRenderState camera,
            LongOpenHashSet clonedEmptySections,
            LongOpenHashSet clonedLoadedChunks,
            CallbackInfo ci) {
        VisibilityGraphBuffers.rotate();
    }

    @Inject(method = "runPartialUpdate", at = @At("HEAD"))
    private void rtxremix$graphHead(CameraRenderState camera, LongSet loadedExpectedChunks, CallbackInfo ci) {
        rtxremix$open(rtxremix$graphScope, "ChunkVisibility.rebuildGraph");
    }

    @Inject(method = "runPartialUpdate", at = @At("RETURN"))
    private void rtxremix$graphReturn(CameraRenderState camera, LongSet loadedExpectedChunks, CallbackInfo ci) {
        rtxremix$budgetHit(rtxremix$graphScope, rtxremix$propagationQueue.budgetHit());
        rtxremix$close(rtxremix$graphScope);
    }

    // Vanilla only propagates when new seeds arrived. Sections left over from a budgeted
    // slice must keep draining even on frames with no new seeds.
    @Redirect(
            method = "runPartialUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/BlockingQueue;isEmpty()Z",
                    ordinal = 0
            ))
    private boolean rtxremix$noPropagationWork(BlockingQueue<?> seeds) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return seeds.isEmpty();
        }
        rtxremix$dropForeignPropagationWork(seeds);
        return seeds.isEmpty() && !rtxremix$propagationQueue.hasPendingWork();
    }

    // Vanilla drains the seeds into a fresh deque and walks it to completion. Handing it the
    // persistent sliced queue instead bounds the walk to this frame's budget and resumes it
    // from the same entries next frame.
    @Redirect(
            method = "runPartialUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Queues;newArrayDeque()Ljava/util/ArrayDeque;"
            ))
    private ArrayDeque<Object> rtxremix$propagationWorkQueue() {
        if (!RtxRemixMod.isRtxEnabled()) {
            rtxremix$propagationQueue.clear();
            return Queues.newArrayDeque();
        }
        rtxremix$propagationQueue.beginSlice();
        return rtxremix$propagationQueue;
    }

    /**
     * Leftover entries index into the state that produced them; a publish invalidates them.
     * Each state owns its seed queue, so the queue vanilla is about to drain identifies the
     * state it loaded for this frame. Re-reading the published state here instead could
     * observe a newer one and leave this frame's leftovers tagged as that state's own.
     */
    private void rtxremix$dropForeignPropagationWork(BlockingQueue<?> seeds) {
        if (rtxremix$propagationSeeds != seeds) {
            rtxremix$propagationQueue.clear();
            rtxremix$propagationSeeds = seeds;
        }
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("HEAD"))
    private void rtxremix$seedHead(BlockPos cameraPosition, Queue<?> queue, CallbackInfo ci) {
        rtxremix$open(rtxremix$seedScope, "ChunkVisibility.seedQueue", rtxremix$sizeOf(queue));
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("RETURN"))
    private void rtxremix$seedReturn(BlockPos cameraPosition, Queue<?> queue, CallbackInfo ci) {
        rtxremix$close(rtxremix$seedScope);
    }

    /**
     * Vanilla's walk allocates in its far-section probe: for each face of a section more than
     * three sections from the camera it ray-marches from a corner of that section back toward
     * the camera in {@link #CEILED_SECTION_DIAGONAL}-block steps, creating two JOML vectors per
     * probe and a BlockPos per step, and it allocates a graph node per reached section. This
     * port keeps vanilla's traversal, arithmetic and evaluation order, holds the ray in doubles
     * and one mutable position and takes nodes from the state's {@link ChunkInfoPool}, so the
     * graph is identical and the walk allocates only what vanilla's containers allocate.
     */
    @Inject(method = "runUpdates", at = @At("HEAD"), cancellable = true)
    private void rtxremix$walkVisibleGraph(
            GraphStorage storage,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean smartCull,
            Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded,
            LongOpenHashSet emptySections,
            LongOpenHashSet loadedChunks,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$walkScope, "ChunkVisibility.walkVisibleGraph", rtxremix$sizeOf(queue));
        if (!RtxRemixMod.isRtxEnabled() || rtxremix$VANILLA_WALK) {
            return;
        }
        try {
            rtxremix$walk(storage, cameraPos, queue, smartCull, onSectionAdded, emptySections, loadedChunks);
        } finally {
            rtxremix$close(rtxremix$walkScope);
        }
        if (rtxremix$LOG_VISIBLE_SET) rtxremix$logVisibleSet("port", storage);
        ci.cancel();
    }

    @Inject(method = "runUpdates", at = @At("RETURN"))
    private void rtxremix$walkReturn(
            GraphStorage storage,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean smartCull,
            Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded,
            LongOpenHashSet emptySections,
            LongOpenHashSet loadedChunks,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$walkScope);
        if (rtxremix$LOG_VISIBLE_SET) rtxremix$logVisibleSet("vanilla", storage);
    }

    /**
     * Order-independent signature of the graph: every reached node's section, step and
     * direction masks, plus the number of chunks the graph waits for. Since 1.21.2 the
     * reached sections live in an octree that cannot be listed, so the slot-indexed node map,
     * which holds every node the walk created, stands in for the old reached-section set.
     */
    private static void rtxremix$logVisibleSet(String walk, GraphStorage storage) {
        long hash = 0;
        int count = 0;
        for (Node node : storage.sectionToNodeMap.nodes) {
            if (node == null) {
                continue;
            }
            count++;
            long state = node.section.getSectionNode()
                    ^ ((long) node.step << 20)
                    ^ ((long) (node.directions & 0xFF) << 36)
                    ^ ((long) (node.sourceDirections & 0xFF) << 44);
            hash += (state * 0x9E3779B97F4A7C15L) ^ 0x632BE59BD9B4E019L;
        }
        int waiting = storage.sectionsWaitingForChunkLoads.size();
        long signature = hash ^ ((long) count << 40) ^ ((long) waiting << 20);
        if (signature != rtxremix$lastVisibleSetSignature) {
            rtxremix$lastVisibleSetSignature = signature;
            RtxRemixMod.LOGGER.info("Visible set ({} walk): {} sections, {} chunks awaited, hash {}",
                    walk, count, waiting, Long.toHexString(hash));
        }
    }

    /** {@code SectionOcclusionGraph.runUpdates}, 26.3. */
    private void rtxremix$walk(
            GraphStorage storage,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean smartCull,
            Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded,
            LongOpenHashSet emptySections,
            LongOpenHashSet loadedChunks) {
        ViewArea area = viewArea;
        SectionToNodeMap nodes = storage.sectionToNodeMap;
        Octree sectionTree = storage.sectionTree;
        Long2ObjectMap<LongList> waitingForChunkLoads = storage.sectionsWaitingForChunkLoads;
        ChunkInfoPool nodePool = ((RenderableChunksBuffers) storage).rtxremix$infoPool();
        Direction[] directions = DIRECTIONS;
        int farDistance = MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE;
        SectionPos cameraSectionPos = SectionPos.of(cameraPos);
        long cameraSectionNode = cameraSectionPos.asLong();
        int cameraSectionX = cameraSectionPos.x();
        int cameraSectionY = cameraSectionPos.y();
        int cameraSectionZ = cameraSectionPos.z();
        // SectionPos.center(): the section origin plus eight.
        int cameraSectionCenterX = SectionPos.sectionToBlockCoord(cameraSectionX) + 8;
        int cameraSectionCenterY = SectionPos.sectionToBlockCoord(cameraSectionY) + 8;
        int cameraSectionCenterZ = SectionPos.sectionToBlockCoord(cameraSectionZ) + 8;
        // One position serves every probed step of every probe in the walk, where vanilla
        // allocated a fresh BlockPos per step. Sound while getRenderSectionAt only derives a
        // section node from the position it is handed; a lookup that kept or keyed by it
        // would alias every probe to the last step.
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        FarChunkProbe.GraphLookup graph = (x, y, z) -> {
            SectionRenderDispatcher.RenderSection passed = area.getRenderSectionAt(probe.set(x, y, z));
            return passed != null && nodes.get(passed) != null;
        };

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            SectionRenderDispatcher.RenderSection currentSection = node.section;
            long sectionNode = currentSection.getSectionNode();
            long chunkNode = ChunkPos.fromSectionNode(sectionNode);
            if (!loadedChunks.contains(chunkNode)) {
                LongList waiting = waitingForChunkLoads.get(chunkNode);
                if (waiting == null) {
                    waiting = new LongArrayList();
                    waitingForChunkLoads.put(chunkNode, waiting);
                }
                waiting.add(sectionNode);
                continue;
            }
            // Vanilla re-reads the section node here; a re-origin on the render thread between
            // the two reads must be seen exactly as vanilla would see it.
            if (!emptySections.contains(node.section.getSectionNode())) {
                if (sectionTree.add(node.section)) {
                    onSectionAdded.accept(node.section);
                }
            } else if (node.section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED, CompiledSectionMesh.EMPTY)) {
                node.section.updateUploadTime();
            }

            boolean distantFromCamera = Math.abs(SectionPos.x(sectionNode) - cameraSectionX) > farDistance
                    || Math.abs(SectionPos.y(sectionNode) - cameraSectionY) > farDistance
                    || Math.abs(SectionPos.z(sectionNode) - cameraSectionZ) > farDistance;

            for (Direction direction : directions) {
                SectionRenderDispatcher.RenderSection neighbour = getRelativeFrom(cameraSectionNode, currentSection, direction);
                if (neighbour == null) {
                    continue;
                }
                if (smartCull && node.hasDirection(direction.getOpposite())) {
                    continue;
                }
                if (smartCull && node.hasSourceDirections()) {
                    SectionMesh sectionMesh = currentSection.getSectionMesh();
                    boolean visible = false;
                    for (int i = 0; i < directions.length; i++) {
                        if (node.hasSourceDirection(i)
                                && sectionMesh.facesCanSeeEachother(directions[i].getOpposite(), direction)) {
                            visible = true;
                            break;
                        }
                    }
                    if (!visible) {
                        continue;
                    }
                }
                if (smartCull && distantFromCamera) {
                    boolean reaches = FarChunkProbe.reachesCamera(
                            SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)),
                            SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)),
                            SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)),
                            direction.getAxis(),
                            cameraSectionCenterX, cameraSectionCenterY, cameraSectionCenterZ,
                            cameraPos.x, cameraPos.y, cameraPos.z,
                            CEILED_SECTION_DIAGONAL,
                            area.maxY(), area.minY(),
                            graph);
                    if (!reaches) {
                        continue;
                    }
                }

                Node existingNode = nodes.get(neighbour);
                if (existingNode != null) {
                    existingNode.addSourceDirection(direction);
                } else {
                    Node newNode = nodePool.acquire(neighbour, direction, node.step + 1);
                    newNode.setDirections(node.directions, direction);
                    queue.add(newNode);
                    nodes.put(neighbour, newNode);
                }
            }
        }
    }

    private static int rtxremix$sizeOf(List<?> values) {
        return values == null ? -1 : values.size();
    }

    private static int rtxremix$sizeOf(Queue<?> values) {
        return values == null ? -1 : values.size();
    }

    private static void rtxremix$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName) {
        rtxremix$open(holder, phaseName, -1);
    }

    private static void rtxremix$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        ProfileScope scope = ProfileScope.begin(phaseName);
        if (captureCount >= 0) {
            scope.captureCount(captureCount);
        }
        stack.push(scope);
    }

    private static void rtxremix$captureCount(ThreadLocal<ArrayDeque<ProfileScope>> holder, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled() || captureCount < 0) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().captureCount(captureCount);
        }
    }

    private static void rtxremix$budgetHit(ThreadLocal<ArrayDeque<ProfileScope>> holder, boolean budgetHit) {
        if (!ProfileScope.isDeepProfilingEnabled() || !budgetHit) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().budgetHit(true);
        }
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
