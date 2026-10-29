/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodCaptureTest {

    private static final Object LEVEL = new Object();

    @Test
    void nothingIsCopiedOrQueuedWhileNoSessionRuns() {
        LodCapture capture = new LodCapture();
        LodSection section = section(capture);

        section.publishGeometry(List.of(oneQuad()), List.of());
        section.publishEnabled(true);
        section.publishClosed();

        assertNull(capture.pollChanged());
        assertNull(section.takePending());
        assertTrue(section.isEnabled(), "the mirror still follows the mod's state");
    }

    @Test
    void aSectionWaitsInTheQueueOnceHoweverOftenItChanges() {
        LodCapture capture = started();
        LodSection section = section(capture);

        section.publishEnabled(true);
        section.publishGeometry(List.of(oneQuad()), List.of());
        section.publishEnabled(false);

        assertSame(section, capture.pollChanged());
        assertNull(capture.pollChanged());
        release(section.takePending());
    }

    @Test
    void aChangePublishedAfterTheSectionWasTakenQueuesItAgain() {
        LodCapture capture = started();
        LodSection section = section(capture);

        section.publishEnabled(true);
        assertSame(section, capture.pollChanged());
        section.publishEnabled(false);

        assertSame(section, capture.pollChanged());
        assertFalse(capture.hasChanges());
    }

    @Test
    void onlyTheNewestCopyWaitsAndItCarriesTheSessionsGeneration() {
        LodCapture capture = started();
        LodSection section = section(capture);

        section.publishGeometry(List.of(oneQuad()), List.of());
        section.publishGeometry(List.of(oneQuad(), oneQuad()), List.of());

        LodGeometry pending = section.takePending();
        assertNotNull(pending);
        assertEquals(2, pending.quadCount());
        assertEquals(capture.generation(), pending.generation());
        assertNull(section.takePending());
        pending.release();
    }

    @Test
    void stoppingReleasesWaitingCopiesAndEndsTheGeneration() {
        LodCapture capture = started();
        LodSection section = section(capture);
        section.publishGeometry(List.of(oneQuad()), List.of());
        int generation = capture.generation();

        capture.stop();

        assertFalse(capture.isCapturing());
        assertNull(capture.pollChanged());
        assertNull(section.takePending());
        assertNotEquals(generation, capture.generation());
        capture.start();
        assertNotEquals(generation, capture.generation(), "a new session never reuses a generation");
    }

    @Test
    void aClosedSectionPublishesNoGeometry() {
        LodCapture capture = started();
        LodSection section = section(capture);
        section.publishClosed();
        assertSame(section, capture.pollChanged());

        section.publishGeometry(List.of(oneQuad()), List.of());

        assertTrue(section.isClosed());
        assertNull(section.takePending());
        assertNull(capture.pollChanged());
    }

    @Test
    void geometryIsReleasedExactlyOnce() {
        LodGeometry geometry = LodGeometry.copyOf(List.of(oneQuad()), List.of(), 1);
        geometry.retain();
        geometry.release();
        assertNotNull(geometry.data());
        geometry.release();

        assertThrows(IllegalStateException.class, geometry::data);
        assertThrows(IllegalStateException.class, geometry::retain);
        assertThrows(IllegalStateException.class, geometry::release);
    }

    @Test
    void theRendererIsAttachedForAsLongAsItExists() {
        LodCapture capture = new LodCapture();
        assertFalse(capture.isAttached());

        LodTerrain terrain = new LodTerrain(new PerformanceTimer(), capture, new IdleSource());
        assertTrue(capture.isAttached(), "attached before any world is drawn");

        terrain.destroy();
        assertFalse(capture.isAttached());
    }

    private static LodCapture started() {
        LodCapture capture = new LodCapture();
        capture.start();
        return capture;
    }

    private static LodSection section(LodCapture capture) {
        return new LodSection(capture, LEVEL, 64, -64, 128, 64);
    }

    private static ByteBuffer oneQuad() {
        return new DhQuadBuffer(1).horizontal(LodMeshBuilder.UP, 0, 0, 16, 16, 70, 0x40A040, 2).finish();
    }

    private static void release(LodGeometry geometry) {
        if (geometry != null) geometry.release();
    }

    /** An LOD mod that never starts, so the renderer exists without capturing anything. */
    private static final class IdleSource implements LodSource {
        @Override
        public void startCapture() {}

        @Override
        public void update() {}

        @Override
        public void stopCapture() {}

        @Override
        public int radiusChunks() {
            return 0;
        }

        @Override
        public String describe() {
            return "an idle LOD mod";
        }
    }
}
