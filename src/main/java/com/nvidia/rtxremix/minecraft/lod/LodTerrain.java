/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.ClientViewDistance;
import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.terrain.TerrainAssetRetirementQueue;
import com.nvidia.rtxremix.minecraft.terrain.TerrainNativeCallStats;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.NVXGPUMemoryInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Far terrain from an LOD mod, path traced as retained Remix instances beyond Minecraft's
 * own terrain.
 *
 * <p>The mod decides which sections exist, at what detail, and which it draws; its hooks
 * publish those decisions through {@link LodCapture}. Here, on the render thread, a
 * section the mod draws is handed with its copied geometry and the current {@link LodClip}
 * to low-priority builder threads, nearest the camera first, and the finished mesh is
 * installed under a frame budget as one instance per section, submitted where it is
 * created and again only when it is shown or hidden. A section the mod stops drawing gives
 * its mesh up once it no longer lingers (see {@link LodSwapTracker}) but keeps its
 * geometry, so it rebuilds without the mod when it is drawn again. Installed meshes are
 * capped by {@link LodBudget}, and the clip follows the columns {@link ChunkManager}
 * publishes, rebuilding only the sections whose share of it changed.
 *
 * <p>Capture runs only while Remix draws the world; {@link #suspend}, and {@link #clear}
 * when the world goes, hand the terrain back to the mod, which then draws it itself.
 * Render thread only.
 */
public final class LodTerrain {

    private static final Logger LOGGER = LoggerFactory.getLogger(LodTerrain.class);

    private static final int BUILD_THREADS = Math.max(1, Integer.getInteger("rtxremix.lod.buildThreads", 3));
    private static final int MAX_BUILDS_IN_FLIGHT = 2 * BUILD_THREADS;
    private static final long INSTALL_BUDGET_NS = Math.max(100_000L,
            Long.getLong("rtxremix.lod.installBudgetNs", 1_000_000L));
    private static final int MAX_LINGER_FRAMES = Math.max(1, Integer.getInteger("rtxremix.lod.maxLingerFrames", 120));
    private static final long MESH_BUDGET_OVERRIDE_MIB = Math.max(0L, Long.getLong("rtxremix.lod.meshBudgetMiB", 0L));
    /** Changes taken from the mod per frame; the rest wait, and a swap waits with them. */
    private static final int MAX_CHANGES_PER_FRAME = 4096;
    /** Frames between clips while columns keep changing, so a section beside loading chunks is not rebuilt every frame. */
    private static final int CLIP_INTERVAL_FRAMES = 4;
    /** Minecraft draws no column farther than its render distance and this many chunks. */
    private static final int CLIP_MARGIN_CHUNKS = 2;
    /**
     * Columns this near the camera's give way as soon as any of their terrain is drawn,
     * surface or not, so no LOD ground stands inside a cave or a room the camera is in.
     */
    private static final int NEAR_COLUMN_CHUNKS = 1;
    static final long MIN_MESH_BUDGET_BYTES = 256L << 20;
    static final long MAX_MESH_BUDGET_BYTES = 2L << 30;
    private static final InstanceInfoBlendEXT BLEND = InstanceInfoBlendEXT.textureTimesVertexColor();
    private static final float GROUND_ROUGHNESS = 0.9f;
    private static final Comparator<LodSection> FARTHEST_FIRST =
            (a, b) -> Double.compare(b.distance, a.distance);

    private record BuildResult(LodSection section, int serial, int epoch, long signature,
                               RemixMesh mesh, Throwable failure) {}

    private final PerformanceTimer timer;
    private final LodCapture capture;
    private final LodSource source;
    private final ExecutorService builder;
    private final ConcurrentLinkedQueue<BuildResult> completed = new ConcurrentLinkedQueue<>();
    private final TerrainNativeCallStats nativeCalls = new TerrainNativeCallStats();
    private final TerrainAssetRetirementQueue retirement = new TerrainAssetRetirementQueue(nativeCalls);
    private final ArrayList<LodSection> live = new ArrayList<>();
    private final ArrayList<LodSection> farthestScratch = new ArrayList<>();
    private final ArrayList<LodSection> deferredScratch = new ArrayList<>();
    private final LodBuildQueue buildQueue = new LodBuildQueue();
    private final LodSwapTracker swaps = new LodSwapTracker(new SceneDisplay(), MAX_LINGER_FRAMES);
    private final Predicate<LodSection> overTerrain = this::liesOverDrawnTerrain;

    private final int cntSections;
    private final int cntInstalledMB;
    private final int cntGeometryMB;
    private final int cntBuilds;
    private final int cntBuildQueue;
    private final int cntClips;
    private final int cntAwaited;
    private final int cntLingering;
    private final int cntHeld;
    private final int cntStarved;

    private RemixAPI api;
    private boolean capturing;
    /** The level whose sections are mirrored; sections of any other are dropped. */
    private Object level;
    private LodBudget budget;
    private boolean budgetFullLogged;
    private boolean buildFailureLogged;
    private LodClip clip = LodClip.NONE;
    private long clipGeneration = Long.MIN_VALUE;
    private int clipFrame;
    private int clipCenterX = Integer.MIN_VALUE;
    private int clipCenterZ = Integer.MIN_VALUE;
    private double cameraX;
    private double cameraZ;
    private int frame;
    private int epoch;
    private int buildsInFlight;
    private int buildsThisFrame;
    private int clipsThisFrame;
    private long geometryBytes;
    private RemixTexture groundTexture;
    private RemixMaterial groundMaterial;
    private boolean groundMaterialFailed;

    public LodTerrain(PerformanceTimer timer, LodCapture capture, LodSource source) {
        this.timer = timer;
        this.capture = capture;
        this.source = source;
        AtomicInteger threadNumber = new AtomicInteger();
        builder = Executors.newFixedThreadPool(BUILD_THREADS, task -> {
            Thread thread = new Thread(task, "RTX Remix LOD builder " + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        cntSections = timer.registerCounter("Lod.Sections");
        cntInstalledMB = timer.registerCounter("Lod.InstalledMB");
        cntGeometryMB = timer.registerCounter("Lod.GeometryMB");
        cntBuilds = timer.registerCounter("Lod.Builds");
        cntBuildQueue = timer.registerCounter("Lod.BuildQueue");
        cntClips = timer.registerCounter("Lod.Clips");
        cntAwaited = timer.registerCounter("Lod.Awaited");
        cntLingering = timer.registerCounter("Lod.Lingering");
        cntHeld = timer.registerCounter("Lod.Held");
        cntStarved = timer.registerCounter("Lod.Starved");
        capture.setAttached(true);
    }

    /**
     * Once per frame while Remix draws the world, after {@link ChunkManager} has published
     * this frame's columns. Starts capture on the first such frame.
     *
     * @param level the level the frame renders
     */
    public void update(RemixAPI api, Object level, Vec3 camera, BlockTextureManager textures, ChunkManager terrain) {
        this.api = api;
        if (!capturing) start(level);
        frame++;
        buildsThisFrame = 0;
        clipsThisFrame = 0;
        nativeCalls.beginFrame();
        cameraX = camera.x;
        cameraZ = camera.z;

        boolean changesPending = drainChanges();
        source.update();
        updateClip(camera, terrain);
        installCompleted();
        swaps.resolve(frame, changesPending, overTerrain);
        startBuilds(textures);
        retirement.drainFrame();
        publishCounters();
    }

    /**
     * Once per frame while Remix does not draw the world: the mod draws its own terrain
     * again, and builds that finish late are freed.
     */
    public void suspend() {
        if (capturing) stop();
        disposeCompleted();
    }

    /**
     * Ends the capture session when the world goes, as {@link #suspend} does, since no frame
     * suspends it until another world is drawn: every mirrored section is dropped and the
     * mod is left to itself. Runs from the client's task queue, so it lands before the next
     * world's first frame, which starts a new session; sections of the next world arrive
     * afresh.
     */
    public void clear() {
        suspend();
    }

    /**
     * The client changed levels. Sections of every other level go at once, without
     * lingering; the new level's arrive as the mod builds them.
     */
    public void onLevelChanged(Object newLevel) {
        if (!capturing || newLevel == level) return;
        level = newLevel;
        for (int i = live.size() - 1; i >= 0; i--) {
            LodSection section = live.get(i);
            if (section.level != newLevel) forget(section, false);
        }
        List<LodSection> lingering = swaps.lingering();
        for (int i = lingering.size() - 1; i >= 0; i--) {
            LodSection section = lingering.get(i);
            if (section.level == newLevel) continue;
            swaps.forget(section);
            dropMesh(section);
        }
        resetClip();
    }

    /**
     * Drops every LOD mesh before the terrain materials they use are rebuilt, keeping the
     * geometry so each section the mod draws rebuilds with the new materials.
     */
    public void releaseMaterials() {
        if (!capturing) return;
        epoch++;
        List<LodSection> lingering = swaps.lingering();
        for (int i = lingering.size() - 1; i >= 0; i--) dropMesh(lingering.get(i));
        swaps.clear();
        for (int i = 0, count = live.size(); i < count; i++) {
            LodSection section = live.get(i);
            dropMesh(section);
            section.ready = false;
            section.starved = false;
            if (section.wanted) {
                swaps.update(section, frame);
                buildQueue.add(section, false);
            }
        }
        // The meshes must be gone before their materials are.
        retirement.clear();
        budget = new LodBudget(budget.limitBytes());
    }

    /** Radius of the LOD terrain drawn, in blocks, or 0 while there is none. */
    public float radiusBlocks() {
        if (!capturing) return 0f;
        double radius = source.radiusChunks() * 16.0;
        if (budget != null) radius = Math.min(radius, budget.radius());
        return (float) radius;
    }

    public void destroy() {
        if (capturing) stop();
        // Queued builds hold geometry references, so they run to the end rather than being dropped.
        builder.shutdown();
        try {
            if (!builder.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("LOD builder threads did not finish within five seconds");
                builder.shutdownNow();
            }
        } catch (InterruptedException e) {
            builder.shutdownNow();
            Thread.currentThread().interrupt();
        }
        disposeCompleted();
        if (groundMaterial != null && !groundMaterial.isDeleted()) groundMaterial.destroy();
        if (groundTexture != null && !groundTexture.isDeleted()) groundTexture.destroy();
        groundMaterial = null;
        groundTexture = null;
        capture.setAttached(false);
    }

    /**
     * The mesh budget for a card with this much video memory: a sixteenth, within fixed
     * bounds. With {@link #dedicatedVideoMemory} it follows {@code LodTerrain.installBudget}
     * in kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
     */
    static long meshBudgetBytes(long videoMemoryBytes) {
        return Math.clamp(videoMemoryBytes / 16, MIN_MESH_BUDGET_BYTES, MAX_MESH_BUDGET_BYTES);
    }

    // --- capture sessions ---

    private void start(Object level) {
        long videoMemory = MESH_BUDGET_OVERRIDE_MIB > 0 ? 0 : dedicatedVideoMemory();
        long budgetBytes = MESH_BUDGET_OVERRIDE_MIB > 0
                ? MESH_BUDGET_OVERRIDE_MIB << 20
                : meshBudgetBytes(videoMemory);
        budget = new LodBudget(budgetBytes);
        budgetFullLogged = false;
        buildFailureLogged = false;
        this.level = level;
        capturing = true;
        capture.start();
        source.startCapture();
        LOGGER.info("LOD terrain capture started for {}: {} MiB mesh budget ({}), {} builder threads",
                source.describe(), budgetBytes >> 20,
                MESH_BUDGET_OVERRIDE_MIB > 0 ? "rtxremix.lod.meshBudgetMiB"
                        : videoMemory > 0 ? (videoMemory >> 20) + " MiB of video memory"
                        : "video memory unknown",
                BUILD_THREADS);
    }

    private void stop() {
        capturing = false;
        capture.stop();
        epoch++;
        dropEverything();
        budget = null;
        level = null;
        resetClip();
        source.stopCapture();
        LOGGER.info("LOD terrain capture stopped; {} draws its own terrain again", source.describe());
    }

    /** Render thread. Dedicated video memory in bytes, or 0 when the driver does not say. */
    private static long dedicatedVideoMemory() {
        if (!GL.getCapabilities().GL_NVX_gpu_memory_info) return 0;
        return (long) GL11C.glGetInteger(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX) << 10;
    }

    /** Forgets every section and frees every mesh at once; nothing drains the retirement after this. */
    private void dropEverything() {
        List<LodSection> lingering = swaps.lingering();
        for (int i = lingering.size() - 1; i >= 0; i--) dropMesh(lingering.get(i));
        swaps.clear();
        for (int i = 0, count = live.size(); i < count; i++) {
            LodSection section = live.get(i);
            section.buildSerial++;
            dropMesh(section);
            releaseGeometry(section);
            section.liveIndex = -1;
            section.wanted = false;
            section.ready = false;
            section.starved = false;
        }
        live.clear();
        buildQueue.clear();
        retirement.clear();
    }

    private void resetClip() {
        clip = LodClip.NONE;
        clipGeneration = Long.MIN_VALUE;
        clipCenterX = Integer.MIN_VALUE;
        clipCenterZ = Integer.MIN_VALUE;
    }

    // --- changes from the mod ---

    /** Applies the mod's queued changes; returns whether some are left for the next frame. */
    private boolean drainChanges() {
        int generation = capture.generation();
        for (int i = 0; i < MAX_CHANGES_PER_FRAME; i++) {
            LodSection section = capture.pollChanged();
            if (section == null) return false;

            LodGeometry geometry = section.takePending();
            if (geometry != null && geometry.generation() != generation) {
                geometry.release();
                geometry = null;
            }
            boolean foreign = section.level != level;
            if (section.isClosed() || foreign) {
                if (geometry != null) geometry.release();
                if (section.liveIndex >= 0) forget(section, !foreign);
                continue;
            }
            if (geometry != null) acceptGeometry(section, geometry);
            if (section.liveIndex >= 0) applyWanted(section);
        }
        return capture.hasChanges();
    }

    private void acceptGeometry(LodSection section, LodGeometry geometry) {
        releaseGeometry(section);
        section.geometry = geometry;
        geometryBytes += geometry.bytes();
        section.buildSerial++;
        section.ready = false;
        section.starved = false;
        if (section.liveIndex < 0) {
            section.liveIndex = live.size();
            live.add(section);
        }
    }

    private void applyWanted(LodSection section) {
        boolean wasLingering = section.swap == LodSwapTracker.LINGERING;
        section.wanted = section.isEnabled();
        swaps.update(section, frame);
        if (section.wanted) {
            if (!section.ready || clip.signature(section) != section.clipSignature) {
                buildQueue.add(section, swaps.overlapsLingering(section));
            }
        } else if (section.swap == LodSwapTracker.LINGERING) {
            if (!wasLingering) hastenReplacements(section);
        } else {
            // Not drawn: the mesh is freed, and rebuilt from the kept geometry when drawn again.
            LodBuildQueue.remove(section);
            dropMesh(section);
            section.ready = false;
        }
    }

    /** Stops mirroring a section; a shown one may linger until what replaces it is ready. */
    private void forget(LodSection section, boolean mayLinger) {
        LodBuildQueue.remove(section);
        section.buildSerial++;
        releaseGeometry(section);
        int index = section.liveIndex;
        LodSection last = live.remove(live.size() - 1);
        if (last != section) {
            live.set(index, last);
            last.liveIndex = index;
        }
        section.liveIndex = -1;
        section.wanted = false;
        section.ready = false;
        if (mayLinger && swaps.close(section, frame)) {
            hastenReplacements(section);
        } else {
            swaps.forget(section);
            dropMesh(section);
        }
    }

    /**
     * Moves the awaited sections over a newly lingering one ahead of the build queue: the
     * mod may show them before it hides what they replace, when their order was not yet
     * known to matter.
     */
    private void hastenReplacements(LodSection lingering) {
        List<LodSection> awaited = swaps.awaited();
        for (int i = 0, count = awaited.size(); i < count; i++) {
            LodSection section = awaited.get(i);
            if (section.overlaps(lingering)) buildQueue.add(section, true);
        }
    }

    private void releaseGeometry(LodSection section) {
        if (section.geometry == null) return;
        geometryBytes -= section.geometry.bytes();
        section.geometry.release();
        section.geometry = null;
    }

    // --- clip ---

    /**
     * Follows the columns Minecraft's terrain draws: when they change, at most every few
     * frames while chunks stream in, and at once when the camera crosses into another
     * chunk. A section wholly given to Minecraft's terrain drops its mesh at once; one whose
     * share changed is rebuilt ahead of the rest.
     */
    private void updateClip(Vec3 camera, ChunkManager terrain) {
        int centerX = ((int) Math.floor(camera.x)) >> 4;
        int centerZ = ((int) Math.floor(camera.z)) >> 4;
        boolean moved = centerX != clipCenterX || centerZ != clipCenterZ;
        long generation = terrain.drawnColumnGeneration();
        if (!moved && (generation == clipGeneration || frame - clipFrame < CLIP_INTERVAL_FRAMES)) return;
        clipGeneration = generation;
        clipFrame = frame;
        clipCenterX = centerX;
        clipCenterZ = centerZ;
        clip = LodClip.around(centerX, centerZ, ClientViewDistance.chunks() + CLIP_MARGIN_CHUNKS,
                (x, z) -> terrain.isColumnDrawn(x, z)
                        || (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) <= NEAR_COLUMN_CHUNKS
                                && terrain.isColumnPublished(x, z)));
        if (moved) {
            buildQueue.reorder();
            budget.relax();
            requeueStarved();
        }
        for (int i = 0, count = live.size(); i < count; i++) {
            LodSection section = live.get(i);
            // A section not built yet is built against whatever clip is current then.
            if (!section.ready && !section.hasMesh()) continue;
            long signature = clip.signature(section);
            if (signature == section.clipSignature) continue;
            clipsThisFrame++;
            if (clip.covers(section)) {
                giveWay(section, signature);
            } else if (section.wanted) {
                buildQueue.add(section, true);
            }
        }
    }

    /** Whether a section's mesh was clipped for columns other than those drawn now. */
    private boolean liesOverDrawnTerrain(LodSection section) {
        long signature = clip.signature(section);
        return signature != 0 && signature != section.clipSignature;
    }

    /** Drops a mesh Minecraft's terrain now wholly covers; nothing needs building there. */
    private void giveWay(LodSection section, long signature) {
        section.buildSerial++;
        dropMesh(section);
        section.ready = true;
        section.starved = false;
        section.clipSignature = signature;
        swaps.update(section, frame);
    }

    // --- budget ---

    /**
     * Leaves a section past the budget's radius unbuilt, with nothing for a swap to wait on.
     * A mesh it already has stays drawn unless it may lie over terrain Minecraft now draws.
     */
    private void starve(LodSection section) {
        if (section.hasMesh() && liesOverDrawnTerrain(section)) dropMesh(section);
        section.starved = true;
        section.ready = true;
        swaps.update(section, frame);
    }

    private void requeueStarved() {
        for (int i = 0, count = live.size(); i < count; i++) {
            LodSection section = live.get(i);
            if (!section.starved || !budget.reaches(section.distanceTo(cameraX, cameraZ))) continue;
            section.starved = false;
            section.ready = false;
            if (section.wanted) buildQueue.add(section, false);
        }
    }

    /** Sections other than {@code except} holding a mesh, farthest first, in the scratch list. */
    private List<LodSection> installedFarthestFirst(LodSection except) {
        farthestScratch.clear();
        for (int i = 0, count = live.size(); i < count; i++) {
            LodSection section = live.get(i);
            if (section == except || !section.hasMesh()) continue;
            section.distance = section.distanceTo(cameraX, cameraZ);
            farthestScratch.add(section);
        }
        farthestScratch.sort(FARTHEST_FIRST);
        return farthestScratch;
    }

    private void logBudgetFull() {
        if (budgetFullLogged) return;
        budgetFullLogged = true;
        LOGGER.info("LOD terrain filled its {} MiB mesh budget; building no farther than {} blocks",
                budget.limitBytes() >> 20, Math.round(budget.radius()));
    }

    // --- builds ---

    private void startBuilds(BlockTextureManager textures) {
        if (buildsInFlight >= MAX_BUILDS_IN_FLIGHT || buildQueue.size() == 0) return;
        // Waits for the water material, which a mesh holds by handle.
        if (textures == null || !textures.isInitialized()) return;
        RemixMaterial ground = groundMaterial();
        if (ground == null) return;
        RemixMaterial water = textures.getWaterSurfaceMaterial();
        LodClip buildClip = clip;
        while (buildsInFlight < MAX_BUILDS_IN_FLIGHT) {
            LodSection section = buildQueue.poll(cameraX, cameraZ);
            if (section == null) break;
            boolean urgent = section.urgent;
            section.urgent = false;
            if (section.liveIndex < 0 || section.geometry == null || !section.wanted) continue;
            long signature = buildClip.signature(section);
            if (section.ready && signature == section.clipSignature) continue;
            if (section.buildInFlight) {
                // Built again, as urgently, once the build it has in flight is in.
                section.urgent = urgent;
                deferredScratch.add(section);
                continue;
            }
            if (!budget.reaches(section.distanceTo(cameraX, cameraZ))) {
                starve(section);
                continue;
            }
            if (buildClip.covers(section)) {
                giveWay(section, signature);
                continue;
            }
            submit(section, buildClip, signature, ground, water);
        }
        for (int i = 0, count = deferredScratch.size(); i < count; i++) {
            LodSection section = deferredScratch.get(i);
            boolean urgent = section.urgent;
            section.urgent = false;
            buildQueue.add(section, urgent);
        }
        deferredScratch.clear();
    }

    private void submit(LodSection section, LodClip buildClip, long signature,
                        RemixMaterial ground, RemixMaterial water) {
        LodGeometry geometry = section.geometry;
        geometry.retain();
        int serial = section.buildSerial;
        int buildEpoch = epoch;
        section.buildInFlight = true;
        buildsInFlight++;
        buildsThisFrame++;
        try {
            builder.execute(() -> completed.add(build(section, geometry, buildClip, signature,
                    serial, buildEpoch, ground, water)));
        } catch (RejectedExecutionException e) {
            geometry.release();
            section.buildInFlight = false;
            buildsInFlight--;
            throw e;
        }
    }

    /** Builder thread. Builds one mesh and releases the build's geometry reference. */
    private static BuildResult build(LodSection section, LodGeometry geometry, LodClip buildClip, long signature,
                                     int serial, int buildEpoch, RemixMaterial ground, RemixMaterial water) {
        RemixMesh mesh = null;
        Throwable failure = null;
        try (ProfileScope scope = ProfileScope.begin("Lod.buildMesh")) {
            scope.captureCount(geometry.quadCount());
            mesh = LodMeshBuilder.build(geometry, buildClip, section.minX, section.minY, section.minZ,
                    ground, water);
        } catch (Throwable t) {
            failure = t;
        } finally {
            geometry.release();
        }
        return new BuildResult(section, serial, buildEpoch, signature, mesh, failure);
    }

    /** Installs finished builds under the frame's budget; one install always goes through. */
    private void installCompleted() {
        if (completed.isEmpty()) return;
        long deadline = System.nanoTime() + INSTALL_BUDGET_NS;
        try (ProfileScope installScope = ProfileScope.begin("Lod.install")) {
            BuildResult result;
            while ((result = completed.poll()) != null) {
                buildsInFlight--;
                LodSection section = result.section();
                section.buildInFlight = false;
                if (result.epoch() != epoch || result.serial() != section.buildSerial
                        || section.liveIndex < 0 || !section.wanted) {
                    dispose(result.mesh());
                    continue;
                }
                if (result.failure() != null) {
                    logBuildFailure(section, result.failure());
                    // Nothing more will come for this geometry; a swap must not wait on it.
                    // The old clip stays recorded, so the next clip change tries again.
                    section.ready = true;
                    swaps.update(section, frame);
                    continue;
                }
                long current = clip.signature(section);
                if (result.signature() != current && clip.covers(section)) {
                    dispose(result.mesh());
                    giveWay(section, current);
                    continue;
                }
                boolean nativeWork = install(section, result.mesh(), result.signature());
                // Built against a clip that has moved on: closer to the truth than what it
                // replaces, and the build for the current clip follows ahead of the rest.
                if (result.signature() != current) buildQueue.add(section, true);
                if (nativeWork && System.nanoTime() >= deadline) return;
            }
        }
    }

    /** Installs a section's new mesh, replacing its old one; returns whether Remix was called. */
    private boolean install(LodSection section, RemixMesh mesh, long signature) {
        if (mesh == null) {
            // The clip left nothing, or the section holds no quads.
            dropMesh(section);
            section.ready = true;
            section.clipSignature = signature;
            swaps.update(section, frame);
            return false;
        }
        long bytes = mesh.estimatedNativeBytes();
        long growth = bytes - section.meshBytes;
        double distance = section.distanceTo(cameraX, cameraZ);
        section.distance = distance;
        if (!budget.fits(distance, growth)) {
            List<LodSection> farther = installedFarthestFirst(section);
            int giveUp = budget.admit(distance, growth, farther);
            logBudgetFull();
            if (giveUp < 0) {
                farthestScratch.clear();
                mesh.disposeData();
                starve(section);
                return false;
            }
            for (int i = 0; i < giveUp; i++) {
                LodSection evicted = farther.get(i);
                dropMesh(evicted);
                starve(evicted);
            }
            farthestScratch.clear();
        }

        RemixInstance instance = null;
        boolean meshCreated = false;
        try {
            try (ProfileScope createScope = ProfileScope.begin("Lod.createMesh")) {
                RemixMeshStore.get().create(mesh);
                meshCreated = true;
                nativeCalls.pageCreated(mesh);
            }
            Transform transform = Transform.identity();
            transform.setTranslation(section.minX, section.minY, section.minZ);
            instance = new RemixInstance(mesh)
                    .setTransform(transform)
                    .setCategoryFlags(InstanceCategoryBit.HIDDEN)
                    .setBlendExt(BLEND)
                    .setDoubleSided(true);
            RemixInstanceStore.get().create(instance);
        } catch (RuntimeException e) {
            LOGGER.error("LOD mesh install failed for {}", section, e);
            if (instance != null && instance.isAlive()) {
                retirement.retire(instance);
            } else if (meshCreated) {
                retirement.retire(mesh);
            } else {
                mesh.disposeData();
            }
            section.ready = true;
            swaps.update(section, frame);
            return true;
        }
        dropMesh(section);
        section.instance = instance;
        section.meshBytes = bytes;
        budget.installed(bytes);
        section.ready = true;
        section.starved = false;
        section.clipSignature = signature;
        nativeCalls.instanceAdded();
        // Submits the new instance, shown or held, in the same frame the old one retires.
        swaps.update(section, frame);
        return true;
    }

    /**
     * Frees a section's mesh, if it has one. The instance leaves the scene at once, so what
     * replaces it never shows beside it; the retirement queue only paces the native
     * destruction.
     */
    private void dropMesh(LodSection section) {
        RemixInstance instance = section.instance;
        if (instance != null) {
            instance.hideFromScene(api);
            retirement.retire(instance);
            if (budget != null) budget.removed(section.meshBytes);
            section.instance = null;
            section.meshBytes = 0;
        }
        section.shown = false;
    }

    /** Frees builds that finished after their capture session or epoch ended. */
    private void disposeCompleted() {
        BuildResult result;
        while ((result = completed.poll()) != null) {
            buildsInFlight--;
            result.section().buildInFlight = false;
            dispose(result.mesh());
        }
    }

    private static void dispose(RemixMesh mesh) {
        if (mesh != null) mesh.disposeData();
    }

    private void logBuildFailure(LodSection section, Throwable failure) {
        if (!buildFailureLogged) {
            buildFailureLogged = true;
            LOGGER.error("LOD mesh build failed for {}; later failures are logged at debug level", section, failure);
        } else {
            LOGGER.debug("LOD mesh build failed for {}", section, failure);
        }
    }

    /** One white texel, so the colour the mod baked into each vertex is the whole albedo. */
    private RemixMaterial groundMaterial() {
        if (groundMaterial != null && !groundMaterial.isDeleted()) return groundMaterial;
        if (groundMaterialFailed) return null;
        ByteBuffer pixel = DirectBufferPool.acquire(4);
        pixel.putInt(0, 0xFFFFFFFF).limit(4);
        RemixTexture texture = null;
        try {
            texture = RemixTextureStore.get().create(new RemixTexture(
                    1, 1, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR, pixel));
            MaterialInfo info = MaterialInfo.builder()
                    .albedoTexture(texture.getHandle())
                    .filterMode(0)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder()
                            .albedoConstant(1f, 1f, 1f)
                            .roughnessConstant(GROUND_ROUGHNESS)
                            .metallicConstant(0f)
                            .build())
                    .build();
            groundMaterial = RemixMaterialStore.get().create(new RemixMaterial(info, List.of(texture), null));
            groundTexture = texture;
        } catch (RuntimeException e) {
            LOGGER.error("Failed to create the LOD terrain material; LOD terrain stays undrawn", e);
            if (texture != null && !texture.isDeleted()) texture.destroy();
            groundMaterial = null;
            groundMaterialFailed = true;
        }
        return groundMaterial;
    }

    private void publishCounters() {
        int starved = 0;
        for (int i = 0, count = live.size(); i < count; i++) {
            if (live.get(i).starved) starved++;
        }
        timer.count(cntSections, live.size());
        timer.count(cntInstalledMB, budget.installedBytes() >> 20);
        timer.count(cntGeometryMB, geometryBytes >> 20);
        timer.count(cntBuilds, buildsThisFrame);
        timer.count(cntBuildQueue, buildQueue.size());
        timer.count(cntClips, clipsThisFrame);
        timer.count(cntAwaited, swaps.awaitedCount());
        timer.count(cntLingering, swaps.lingeringCount());
        timer.count(cntHeld, swaps.heldCount());
        timer.count(cntStarved, starved);
    }

    /** Shows and hides installed meshes for the swap tracker. */
    private final class SceneDisplay implements LodSwapTracker.Display {
        @Override
        public void setShown(LodSection section, boolean shown) {
            RemixInstance instance = section.instance;
            if (instance == null) {
                section.shown = false;
                return;
            }
            instance.setCategoryFlags(shown ? 0 : InstanceCategoryBit.HIDDEN);
            instance.drawFast(api);
            section.shown = shown;
        }

        @Override
        public void lingerEnded(LodSection section) {
            dropMesh(section);
            section.ready = false;
        }
    }
}
