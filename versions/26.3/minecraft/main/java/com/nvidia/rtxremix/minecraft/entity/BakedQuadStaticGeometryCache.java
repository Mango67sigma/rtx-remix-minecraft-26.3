/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/**
 * Cache attached to vanilla baked quads by a mixin.
 *
 * <p>The digest contains only bake-time geometry: positions, normal, UVs and the
 * sprite's texcoord bounds. A {@code BakedQuad} is an immutable record whose sprite
 * never changes, so the digest is computed once per quad object and needs no
 * content verification; a resource reload bakes new quads with empty caches.
 * Per-render tint and brightness remain outside the cache and are folded into the
 * mesh key on every draw.
 */
public interface BakedQuadStaticGeometryCache {

    /** Whether {@link #rtxremix$getStaticGeometryHash()} holds a digest for this quad. */
    boolean rtxremix$hasStaticGeometryHash();

    long rtxremix$getStaticGeometryHash();

    void rtxremix$setStaticGeometryHash(long hash);
}
