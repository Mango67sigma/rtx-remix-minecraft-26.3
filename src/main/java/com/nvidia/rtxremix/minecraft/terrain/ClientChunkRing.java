/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.mixin.ClientChunkCacheAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ClientChunkCacheStorageAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The client chunk cache's ring of chunk slots, as the terrain pipeline reads it.
 *
 * <p>{@code ClientChunkCache} keeps its chunks in a square window of slots around the view
 * centre the server last sent, and returns only the chunks the window covers. Moving the
 * centre ({@code updateViewCenter}) only moves the window. A chunk it leaves behind stays in
 * its slot, and an unload the server sends for it afterwards is ignored, because
 * {@code drop} acts only inside the window; the chunk leaves when a chunk for another
 * position reuses the slot, and Fabric's {@code CHUNK_UNLOAD} reports it only then. The
 * server sends the new centre before the unloads it owes, and the ring reaches at least two
 * chunks past the server's view, so a jump wider than that margin leaves the far edge of the
 * old view behind this way and a teleport leaves all of it. {@link #reportDepartures}
 * reports those chunks as unloaded when the centre moves; the slot reuse reports them a
 * second time, which finds nothing left to remove.
 */
public final class ClientChunkRing {

    private ClientChunkRing() {
    }

    /**
     * Reports every chunk the ring is about to leave behind to the chunk manager as unloaded,
     * the way Fabric's {@code CHUNK_UNLOAD} handler does. Called at the head of
     * {@code ClientChunkCache.updateViewCenter}, while the cache still answers for the old
     * centre. Acts only on the render thread, where vanilla applies the centre packet, and
     * only for the chunk cache of the level being rendered.
     */
    public static void reportDepartures(ClientChunkCache cache, int newCenterX, int newCenterZ) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (!client.isSameThread() || level == null || level.getChunkSource() != cache) {
            return;
        }
        ChunkManager chunkManager = chunkManager();
        if (chunkManager == null) {
            return;
        }
        ClientChunkCacheStorageAccessor ring = ring(cache);
        RingDepartures departures = new RingDepartures(
                ring.rtxremix$viewCenterX(), ring.rtxremix$viewCenterZ(),
                newCenterX, newCenterZ, ring.rtxremix$chunkRadius());
        while (departures.next()) {
            LevelChunk chunk = cache.getChunk(departures.x(), departures.z(), ChunkStatus.FULL, false);
            if (chunk != null) {
                chunkManager.onChunkUnloaded(chunk.getPos());
            }
        }
    }

    /**
     * Whether the ring has moved past the position while the position's slot still holds its
     * chunk, which is a chunk the ring left behind with no unload reported for it yet.
     */
    static boolean holdsChunkOutsideRing(ClientChunkCache cache, int chunkX, int chunkZ) {
        ClientChunkCache.Storage storage = ((ClientChunkCacheAccessor) cache).rtxremix$storage();
        ClientChunkCacheStorageAccessor ring = (ClientChunkCacheStorageAccessor) (Object) storage;
        if (RingDepartures.covers(ring.rtxremix$viewCenterX(), ring.rtxremix$viewCenterZ(),
                ring.rtxremix$chunkRadius(), chunkX, chunkZ)) {
            return false;
        }
        LevelChunk held = storage.getChunk(ring.rtxremix$getIndex(chunkX, chunkZ));
        if (held == null) {
            return false;
        }
        ChunkPos pos = held.getPos();
        return pos.x() == chunkX && pos.z() == chunkZ;
    }

    private static ClientChunkCacheStorageAccessor ring(ClientChunkCache cache) {
        return (ClientChunkCacheStorageAccessor) (Object) ((ClientChunkCacheAccessor) cache).rtxremix$storage();
    }

    private static ChunkManager chunkManager() {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        return renderer == null ? null : renderer.getChunkManager();
    }
}
