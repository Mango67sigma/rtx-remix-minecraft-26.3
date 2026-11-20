/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodBuildQueueTest {

    private final LodCapture capture = new LodCapture();

    @Test
    void theNearestSectionIsBuiltFirst() {
        LodBuildQueue queue = new LodBuildQueue();
        LodSection far = section(1024, 0);
        LodSection near = section(128, 0);
        LodSection middle = section(-512, 0);
        queue.add(far, false);
        queue.add(near, false);
        queue.add(middle, false);

        assertSame(near, queue.poll(0, 0));
        assertSame(middle, queue.poll(0, 0));
        assertSame(far, queue.poll(0, 0));
        assertNull(queue.poll(0, 0));
        assertFalse(near.buildQueued);
    }

    @Test
    void anUrgentSectionGoesAheadOfNearerOnes() {
        LodBuildQueue queue = new LodBuildQueue();
        LodSection near = section(64, 0);
        LodSection far = section(2048, 0);
        queue.add(near, false);
        queue.add(far, false);
        queue.add(far, true);

        assertSame(far, queue.poll(0, 0));
        assertTrue(far.urgent, "a section taken keeps its urgency for the caller to act on");
        assertSame(near, queue.poll(0, 0));
        assertFalse(near.urgent);
    }

    @Test
    void aMovedCameraReordersTheQueue() {
        LodBuildQueue queue = new LodBuildQueue();
        LodSection east = section(1024, 0);
        LodSection west = section(-1024, 0);
        LodSection middle = section(0, 0);
        queue.add(east, false);
        queue.add(west, false);
        queue.add(middle, false);
        // Taking the east leaves the other two sorted for this camera, the middle next.
        assertSame(east, queue.poll(900, 0));

        queue.reorder();

        assertSame(west, queue.poll(-900, 0));
    }

    @Test
    void aRemovedSectionIsSkippedAndCanBeQueuedAgain() {
        LodBuildQueue queue = new LodBuildQueue();
        LodSection section = section(0, 0);
        queue.add(section, true);
        LodBuildQueue.remove(section);

        assertNull(queue.poll(0, 0));
        assertEquals(0, queue.size());

        queue.add(section, false);
        queue.add(section, false);
        assertSame(section, queue.poll(0, 0));
        assertNull(queue.poll(0, 0), "a section is queued once");
    }

    private LodSection section(int minX, int minZ) {
        return new LodSection(capture, null, minX, -64, minZ, 64);
    }
}
