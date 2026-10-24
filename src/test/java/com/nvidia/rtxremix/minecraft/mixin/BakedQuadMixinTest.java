/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedQuadMixinTest {

    @Test
    void zeroIsAValidCachedHash() {
        BakedQuadMixin cache = new BakedQuadMixin();

        assertFalse(cache.rtxremix$hasStaticGeometryHash());
        cache.rtxremix$setStaticGeometryHash(0L);

        assertTrue(cache.rtxremix$hasStaticGeometryHash());
        assertEquals(0L, cache.rtxremix$getStaticGeometryHash());
    }

    @Test
    void settingAgainReplacesTheHash() {
        BakedQuadMixin cache = new BakedQuadMixin();
        cache.rtxremix$setStaticGeometryHash(7L);
        cache.rtxremix$setStaticGeometryHash(8L);

        assertTrue(cache.rtxremix$hasStaticGeometryHash());
        assertEquals(8L, cache.rtxremix$getStaticGeometryHash());
    }
}
