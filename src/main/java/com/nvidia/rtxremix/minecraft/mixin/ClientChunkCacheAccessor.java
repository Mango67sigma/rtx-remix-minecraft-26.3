/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The client chunk cache's ring of chunk slots, for {@code ClientChunkRing}. A view radius
 * change replaces the storage, so callers read it afresh each time rather than keep it.
 */
@Mixin(ClientChunkCache.class)
public interface ClientChunkCacheAccessor {
    @Accessor("storage")
    ClientChunkCache.Storage rtxremix$storage();
}
