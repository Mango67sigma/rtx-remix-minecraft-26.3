/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkManagerDrawnColumnTest {

    @Test
    void aColumnIsDrawnOncePagesReachItsHighestSectionHoldingABlock() {
        assertTrue(ChunkManager.reachesSurface(5, 5));
        assertTrue(ChunkManager.reachesSurface(6, 5));
        assertFalse(ChunkManager.reachesSurface(2, 5), "its caves are published, its ground not yet");
        assertTrue(ChunkManager.reachesSurface(Integer.MIN_VALUE, Integer.MIN_VALUE),
                "a chunk of air has nothing to wait for");
    }

    @Test
    void publishingRemovingAndClearingColumnsMoveTheDrawnGeneration() throws Exception {
        ChunkManager manager = new ChunkManager(null);
        try {
            long initial = manager.drawnColumnGeneration();
            assertFalse(manager.isColumnPublished(3, -2));
            assertFalse(manager.isColumnDrawn(3, -2));

            // A capture identical to what the column shows publishes it with nothing to build.
            markColumnDirty(manager, 3, -2);
            assertTrue(manager.isColumnPublished(3, -2));
            assertTrue(manager.isColumnDrawn(3, -2), "with no world loaded nothing lies above it");
            long published = manager.drawnColumnGeneration();
            assertTrue(published > initial);

            markColumnDirty(manager, 3, -2);
            assertEquals(published, manager.drawnColumnGeneration(), "republishing the same state changes nothing");

            removeRenderColumn(manager, 3, -2);
            assertFalse(manager.isColumnPublished(3, -2));
            long removed = manager.drawnColumnGeneration();
            assertTrue(removed > published);

            markColumnDirty(manager, 4, -2);
            long beforeClear = manager.drawnColumnGeneration();
            manager.clear();
            assertFalse(manager.isColumnPublished(4, -2));
            assertTrue(manager.drawnColumnGeneration() > beforeClear,
                    "a clear, as a terrain reload starts with, makes the LOD clip look again");
        } finally {
            manager.destroy();
        }
    }

    private static void markColumnDirty(ChunkManager manager, int cx, int cz) throws Exception {
        Method method = ChunkManager.class.getDeclaredMethod(
                "markColumnDirty", int.class, int.class, int.class, boolean.class);
        method.setAccessible(true);
        method.invoke(manager, cx, cz, 0, false);
    }

    private static void removeRenderColumn(ChunkManager manager, int cx, int cz) throws Exception {
        Method method = ChunkManager.class.getDeclaredMethod("removeRenderColumn", int.class, int.class);
        method.setAccessible(true);
        method.invoke(manager, cx, cz);
    }
}
