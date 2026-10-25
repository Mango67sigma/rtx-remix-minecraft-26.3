/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The window of the client chunk cache's ring, for {@code ClientChunkRing}: the centre the
 * server last sent, the radius, and the slot a position maps to. The cache returns a chunk
 * only while the window covers its position.
 */
@Mixin(ClientChunkCache.Storage.class)
public interface ClientChunkCacheStorageAccessor {
    @Accessor("viewCenterX")
    int rtxremix$viewCenterX();

    @Accessor("viewCenterZ")
    int rtxremix$viewCenterZ();

    /** The server's view distance plus three, never less than five. */
    @Accessor("chunkRadius")
    int rtxremix$chunkRadius();

    /** The slot a position maps to, whether or not the window covers it. */
    @Invoker("getIndex")
    int rtxremix$getIndex(int chunkX, int chunkZ);
}
