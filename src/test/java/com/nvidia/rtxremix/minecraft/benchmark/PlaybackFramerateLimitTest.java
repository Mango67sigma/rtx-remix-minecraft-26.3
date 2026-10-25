/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackFramerateLimitTest {
    private static final int CONFIGURED = 144;
    /** Vanilla's SHORT_AFK result, min(144, 30). */
    private static final int SHORT_AFK = 30;
    /** Vanilla's LONG_AFK and WINDOW_ICONIFIED result. */
    private static final int TEN = 10;
    private static final int UNFOCUSED_CAP = 20;

    @Test
    void idlePlayerKeepsVanillaAfkThrottle() {
        assertEquals(SHORT_AFK, resolve(SHORT_AFK, false, true, true, false, false));
        assertEquals(TEN, resolve(TEN, false, true, true, false, false));
    }

    @Test
    void benchmarkIgnoresAfkThrottle() {
        assertEquals(CONFIGURED, resolve(SHORT_AFK, false, true, true, true, false));
        assertEquals(CONFIGURED, resolve(TEN, false, true, true, true, false));
    }

    @Test
    void cameraPathPlaybackIgnoresAfkThrottle() {
        assertEquals(CONFIGURED, resolve(SHORT_AFK, false, true, true, false, true));
        assertEquals(CONFIGURED, resolve(TEN, false, true, true, false, true));
        // Without Remix the playback still runs, and still ignores the idle clock.
        assertEquals(CONFIGURED, resolve(SHORT_AFK, false, true, false, false, true));
    }

    @Test
    void iconifiedWindowStaysThrottledDuringPlayback() {
        assertEquals(TEN, resolve(TEN, true, false, true, true, false));
        assertEquals(TEN, resolve(TEN, true, false, true, false, true));
    }

    @Test
    void unfocusedWindowIsCappedWhileRemixRenders() {
        assertEquals(UNFOCUSED_CAP, resolve(CONFIGURED, false, false, true, false, false));
        // Vanilla's own lower AFK limit still wins.
        assertEquals(TEN, resolve(TEN, false, false, true, false, false));
        // Without Remix, vanilla alone decides.
        assertEquals(CONFIGURED, resolve(CONFIGURED, false, false, false, false, false));
    }

    @Test
    void benchmarkIsExemptFromUnfocusedCapButPlaybackIsNot() {
        assertEquals(CONFIGURED, resolve(SHORT_AFK, false, false, true, true, false));
        assertEquals(UNFOCUSED_CAP, resolve(SHORT_AFK, false, false, true, false, true));
    }

    @Test
    void focusedActivePlayerGetsVanillaLimit() {
        assertEquals(CONFIGURED, resolve(CONFIGURED, false, true, true, false, false));
    }

    private static int resolve(
            int vanillaLimit,
            boolean iconified,
            boolean windowActive,
            boolean remixRendering,
            boolean benchmarkRunning,
            boolean pathPlaying) {
        return PlaybackFramerateLimit.resolve(
                vanillaLimit, CONFIGURED, iconified, windowActive, remixRendering,
                benchmarkRunning, pathPlaying, UNFOCUSED_CAP);
    }
}
