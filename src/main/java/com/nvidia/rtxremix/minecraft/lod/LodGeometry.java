/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One copy of a section's quads in the LOD mod's own vertex format (see
 * {@link LodMeshBuilder}), held in a pooled direct buffer.
 *
 * <p>The section keeps a reference so that a clip change or a material reload rebuilds its
 * mesh without asking the mod again, and each build in flight holds one more. The last
 * release returns the buffer to {@link DirectBufferPool}; releasing more often than
 * retained fails loudly rather than pooling the buffer twice.
 *
 * <p>Follows {@code LodGeometry} in kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
final class LodGeometry {

    /** Bytes of one quad: four vertices of {@link LodMeshBuilder#VERTEX_BYTES}. */
    static final int QUAD_BYTES = 4 * LodMeshBuilder.VERTEX_BYTES;

    private final ByteBuffer data;
    private final int quadCount;
    private final int generation;
    private final AtomicInteger references = new AtomicInteger(1);

    private LodGeometry(ByteBuffer data, int quadCount, int generation) {
        this.data = data;
        this.quadCount = quadCount;
        this.generation = generation;
    }

    /**
     * Copies the remaining bytes of every buffer, whole quads only: a torn tail would shift
     * every later vertex. The caller keeps ownership of the buffers, whose positions are
     * left unchanged.
     */
    static LodGeometry copyOf(List<ByteBuffer> opaque, List<ByteBuffer> transparent, int generation) {
        int bytes = Math.addExact(wholeQuadBytes(opaque), wholeQuadBytes(transparent));
        if (bytes == 0) return new LodGeometry(null, 0, generation);
        ByteBuffer data = DirectBufferPool.acquire(bytes);
        put(data, opaque);
        put(data, transparent);
        data.flip();
        return new LodGeometry(data, bytes / QUAD_BYTES, generation);
    }

    /**
     * The copied quads, positioned at 0 with the payload as limit, or null for a section
     * without quads. Callers read through a duplicate.
     *
     * @throws IllegalStateException once the last reference has been released
     */
    ByteBuffer data() {
        if (references.get() <= 0) throw new IllegalStateException("LOD geometry read after release");
        return data;
    }

    int quadCount() {
        return quadCount;
    }

    /** The capture session the copy was made in. */
    int generation() {
        return generation;
    }

    long bytes() {
        return (long) quadCount * QUAD_BYTES;
    }

    /** Adds a reference for a reader on another thread; pair with {@link #release()}. */
    void retain() {
        int previous = references.getAndUpdate(count -> count > 0 ? count + 1 : count);
        if (previous <= 0) throw new IllegalStateException("LOD geometry retained after release");
    }

    /** Drops one reference; the last returns the buffer to the pool. */
    void release() {
        int remaining = references.decrementAndGet();
        if (remaining > 0) return;
        if (remaining < 0) {
            references.incrementAndGet();
            throw new IllegalStateException("LOD geometry released more often than retained");
        }
        DirectBufferPool.release(data);
    }

    private static int wholeQuadBytes(List<ByteBuffer> buffers) {
        int bytes = 0;
        for (int i = 0, count = buffers.size(); i < count; i++) {
            int remaining = buffers.get(i).remaining();
            bytes = Math.addExact(bytes, remaining - remaining % QUAD_BYTES);
        }
        return bytes;
    }

    private static void put(ByteBuffer data, List<ByteBuffer> buffers) {
        for (int i = 0, count = buffers.size(); i < count; i++) {
            ByteBuffer source = buffers.get(i).duplicate();
            int remaining = source.remaining();
            source.limit(source.position() + remaining - remaining % QUAD_BYTES);
            data.put(source);
        }
    }
}
