/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.BakedQuadStaticGeometryCache;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Stores the bake-time geometry digest used by Remix entity mesh pooling. {@code BakedQuad}
 * is an immutable record: the JVM admits the extra fields a mixin adds to a record class,
 * only the language forbids declaring them, so the digest rides on the quad itself and is
 * computed once per quad object. A resource reload bakes new quads with empty caches.
 */
@Mixin(BakedQuad.class)
public class BakedQuadMixin implements BakedQuadStaticGeometryCache {

    private boolean rtxremix$hasStaticGeometryHash;
    private long rtxremix$staticGeometryHash;

    @Override
    public boolean rtxremix$hasStaticGeometryHash() {
        return rtxremix$hasStaticGeometryHash;
    }

    @Override
    public long rtxremix$getStaticGeometryHash() {
        return rtxremix$staticGeometryHash;
    }

    @Override
    public void rtxremix$setStaticGeometryHash(long hash) {
        rtxremix$staticGeometryHash = hash;
        rtxremix$hasStaticGeometryHash = true;
    }
}
