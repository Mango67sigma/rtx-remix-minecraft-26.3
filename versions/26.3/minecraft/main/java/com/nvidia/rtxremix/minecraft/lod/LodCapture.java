/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The hand-over between an LOD mod's threads and the render thread.
 *
 * <p>The mod's hooks publish what changes about a section through {@link LodSection}, and
 * the section then waits in this queue until the render thread takes it. A section is
 * queued at most once however often it changes, so the queue is bounded by the sections
 * the mod holds. Each capture session has its own generation, which every copy of a
 * section's geometry carries: a copy made for an earlier session, or while none runs, is
 * never installed. Only the render thread starts and stops a session.
 */
public final class LodCapture {

    private static final LodCapture SHARED = new LodCapture();

    private final ConcurrentLinkedQueue<LodSection> changed = new ConcurrentLinkedQueue<>();
    private volatile boolean capturing;
    private volatile int generation;
    private volatile boolean attached;

    LodCapture() {}

    /** The hub the mixins publish to. */
    public static LodCapture shared() {
        return SHARED;
    }

    /**
     * Any thread. Whether Remix draws the LOD mod's terrain this session, so that the mod
     * copies it out and draws none of it itself.
     */
    public boolean isCapturing() {
        return capturing;
    }

    /**
     * Any thread. Whether a renderer that draws LOD terrain exists, for as long as it
     * does: the mod must then leave alone the vanilla options the renderer reads, even
     * while capture is paused.
     */
    public boolean isAttached() {
        return attached;
    }

    /** Any thread. The current session's generation, which a geometry copy is tagged with. */
    int generation() {
        return generation;
    }

    /** Render thread. A renderer that draws LOD terrain exists, or no longer does. */
    void setAttached(boolean attached) {
        this.attached = attached;
    }

    /** Render thread. Starts a session; copies made before it are stale from here on. */
    void start() {
        generation++;
        capturing = true;
    }

    /**
     * Render thread. Ends the session and releases every copy still waiting. A copy that
     * a hook finishes after this releases itself (see {@link LodSection#publishGeometry}).
     */
    void stop() {
        capturing = false;
        generation++;
        LodSection section;
        while ((section = pollChanged()) != null) {
            LodGeometry pending = section.takePending();
            if (pending != null) pending.release();
        }
    }

    /** Any thread. Queues the section for the render thread unless it already waits. */
    void markChanged(LodSection section) {
        if (section.claimQueueSlot()) changed.add(section);
    }

    /**
     * Render thread. The next changed section, already free to be queued again, so a
     * change published while the render thread reads it queues it anew.
     */
    LodSection pollChanged() {
        LodSection section = changed.poll();
        if (section != null) section.releaseQueueSlot();
        return section;
    }

    /** Render thread. Whether changes are left for a later frame. */
    boolean hasChanges() {
        return !changed.isEmpty();
    }
}
