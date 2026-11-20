/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.asset.RemixInstance;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Remix's mirror of one section an LOD mod renders: a square of the world at one detail.
 *
 * <p>The mod's threads report the section's new geometry, whether the mod draws it, and its
 * closing through the {@code publish} methods, which any thread may call. They only record
 * the change and queue the section in its {@link LodCapture}; {@link LodTerrain} applies it
 * on the render thread. At most one geometry copy waits per section, the newest, so a
 * section that changes faster than the render thread drains holds no backlog. Every
 * package-private field below belongs to the render thread.
 *
 * <p>The publish-and-queue pattern follows {@code LodSection} in
 * kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
public final class LodSection {

    private final LodCapture capture;
    /** The level the section belongs to, compared by identity; never dereferenced. */
    final Object level;
    /** World block position of the section's minimum corner, y being the level's minimum height. */
    final int minX;
    final int minY;
    final int minZ;
    /** Width of the section's square in blocks. */
    final int width;

    private final AtomicReference<LodGeometry> pending = new AtomicReference<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile boolean enabled;
    private volatile boolean closed;

    // Render thread only.
    /** The geometry the next mesh is built from; owned, so it is released when replaced. */
    LodGeometry geometry;
    /** Index in {@link LodTerrain}'s live list, or -1 while the section is not mirrored. */
    int liveIndex = -1;
    /** Whether the mod draws the section, as of the last change the render thread applied. */
    boolean wanted;
    /** Whether a mesh of the current geometry is installed or none is to be built: empty, clipped away, starved. */
    boolean ready;
    /** Whether the section lies past what the mesh budget builds, so it is not rebuilt and keeps any mesh it has. */
    boolean starved;
    /** The installed mesh's instance, or null. Kept while a newer mesh builds, so nothing goes missing. */
    RemixInstance instance;
    /** Whether the instance is in the scene unhidden. */
    boolean shown;
    /** Estimated native bytes of the installed mesh. */
    long meshBytes;
    /** Signature of the clip the installed mesh was built against (see {@link LodClip#signature}). */
    long clipSignature;
    /** Bumped whenever the geometry changes or the section is dropped; a build of an older serial is stale. */
    int buildSerial;
    boolean buildQueued;
    boolean buildInFlight;
    /** Whether the next build goes ahead of the distance order: it ends a swap or corrects a clip. */
    boolean urgent;
    /** Horizontal distance from the camera to the section, as of the last ordering. */
    double distance;
    /** The section's part in a detail swap, one of {@code LodSwapTracker}'s states. */
    byte swap;
    /** Index in the swap tracker's list for {@link #swap}, or -1. */
    int swapIndex = -1;
    /** Frame the section began to linger. */
    int lingerSince;

    /**
     * @param capture the hub the section publishes to
     * @param level   the level the section belongs to
     * @param minX    world block x of the section's minimum corner
     * @param minY    the level's minimum height, which the mod's y coordinates count from
     * @param minZ    world block z of the section's minimum corner
     * @param width   width of the section in blocks
     */
    public LodSection(LodCapture capture, Object level, int minX, int minY, int minZ, int width) {
        this.capture = capture;
        this.level = level;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.width = width;
    }

    /**
     * Any thread. Copies the quads the mod is about to upload. The mod reuses these buffers
     * once its upload completes, so the copy is made before this returns; the buffers'
     * positions are left as they were. Calls for one section must not overlap.
     */
    public void publishGeometry(List<ByteBuffer> opaque, List<ByteBuffer> transparent) {
        // Read after the session flag, which a start sets after its generation.
        if (closed || !capture.isCapturing()) return;
        int generation = capture.generation();
        LodGeometry replaced = pending.getAndSet(LodGeometry.copyOf(opaque, transparent, generation));
        if (replaced != null) replaced.release();
        capture.markChanged(this);
        // A session that ended after the check above may already have drained the queue;
        // nothing would release this copy then.
        if (!capture.isCapturing() || capture.generation() != generation) {
            LodGeometry orphan = pending.getAndSet(null);
            if (orphan != null) orphan.release();
        }
    }

    /** Any thread. The mod starts or stops drawing the section. */
    public void publishEnabled(boolean enabled) {
        this.enabled = enabled;
        if (capture.isCapturing()) capture.markChanged(this);
    }

    /** Any thread. The mod has dropped the section; it will publish nothing more. */
    public void publishClosed() {
        closed = true;
        if (capture.isCapturing()) capture.markChanged(this);
    }

    /** Whether the mod draws the section, as last published. */
    boolean isEnabled() {
        return enabled;
    }

    boolean isClosed() {
        return closed;
    }

    /** Render thread. Takes the waiting geometry copy, which the caller then owns. */
    LodGeometry takePending() {
        return pending.getAndSet(null);
    }

    boolean claimQueueSlot() {
        return queued.compareAndSet(false, true);
    }

    void releaseQueueSlot() {
        queued.set(false);
    }

    /** Whether a mesh is installed for the section, current or about to be replaced. */
    boolean hasMesh() {
        return instance != null;
    }

    /** Whether the two sections' squares overlap. */
    boolean overlaps(LodSection other) {
        return minX < other.minX + other.width && other.minX < minX + width
                && minZ < other.minZ + other.width && other.minZ < minZ + width;
    }

    /** Horizontal distance in blocks from a point to the nearest edge of the section; 0 inside it. */
    double distanceTo(double x, double z) {
        double dx = Math.max(0.0, Math.max(minX - x, x - (minX + width)));
        double dz = Math.max(0.0, Math.max(minZ - z, z - (minZ + width)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String toString() {
        return "LodSection[" + minX + ", " + minZ + " width " + width + "]";
    }
}
