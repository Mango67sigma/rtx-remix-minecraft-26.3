/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tints a Fabric {@code BlockTintsFactory} collected are indexed as vanilla indexes its tint
 * sources ({@code ModelBlockRenderer.computeTintColor}): by the quad's tint index, white past
 * the end.
 */
class BlockTintFactoryTest {

    @Test
    void factoryTintsAreIndexedByTintIndexAndWhitePastTheEnd() {
        IntArrayList tints = IntArrayList.of(0x112233, 0x445566);
        assertEquals(0x112233, BlockQuadEmitter.factoryTint(tints, 0));
        assertEquals(0x445566, BlockQuadEmitter.factoryTint(tints, 1));
        assertEquals(-1, BlockQuadEmitter.factoryTint(tints, 2));
        assertEquals(-1, BlockQuadEmitter.factoryTint(tints, -1));
    }

    @Test
    void aBlockWithoutAFactoryOrWithAnEmptyCollectionIsUntinted() {
        assertEquals(-1, BlockQuadEmitter.factoryTint(new IntArrayList(), 0));
    }
}
