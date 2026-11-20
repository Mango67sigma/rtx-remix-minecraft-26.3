/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.benchmark;

/**
 * Decides the frame-rate limit {@code FramerateLimitTracker.getFramerateLimit} returns once the
 * mod's own throttles and exemptions are applied.
 *
 * <p>Vanilla 26.3 throttles an idle window: after 60 s without input it caps at
 * {@code min(limit, 30)} ({@code SHORT_AFK}) and after 10 min at 10 fps ({@code LONG_AFK}), with
 * {@code Options.inactivityFpsLimit} at its default {@code AFK}. Only real keyboard, mouse and
 * controller events reset that clock. A benchmark, a still-shot run (a benchmark state) or camera
 * path playback runs unattended and must not slow down because nobody touched the input, so while
 * one runs the limit is the configured Max Framerate for every throttle reason except an iconified
 * window (or an unfocused exclusive-fullscreen one, which vanilla reports the same way): nothing is
 * shown then, so there is nothing to measure or watch.
 *
 * <p>The mod also caps an unfocused but visible window ({@code UnfocusedFramerateLimit}) while
 * Remix renders. A benchmark is exempt from that cap, because it measures renderer throughput;
 * camera path playback is not, because a viewer who tabbed away is not watching it.
 *
 * <p>Pure function of its arguments, so it is tested headless.
 */
public final class PlaybackFramerateLimit {

    private PlaybackFramerateLimit() {
    }

    /**
     * @param vanillaLimit     what vanilla's {@code getFramerateLimit} returned, throttles included
     * @param configuredLimit  the Max Framerate option ({@code FramerateLimitTracker.framerateLimit})
     * @param iconified        whether vanilla's throttle reason is {@code WINDOW_ICONIFIED}
     * @param windowActive     whether the game window has focus
     * @param remixRendering   whether an initialized Remix renderer draws the frames
     * @param benchmarkRunning whether a benchmark or still-shot run is in progress
     * @param pathPlaying      whether a camera path is playing (not paused)
     * @param unfocusedCap     the unfocused-window cap, {@code UnfocusedFramerateLimit.get()}
     * @return the limit to apply this frame
     */
    public static int resolve(
            int vanillaLimit,
            int configuredLimit,
            boolean iconified,
            boolean windowActive,
            boolean remixRendering,
            boolean benchmarkRunning,
            boolean pathPlaying,
            int unfocusedCap) {
        if (iconified) {
            return vanillaLimit;
        }
        int limit = benchmarkRunning || pathPlaying ? configuredLimit : vanillaLimit;
        if (windowActive || !remixRendering || benchmarkRunning) {
            return limit;
        }
        return Math.min(limit, unfocusedCap);
    }
}
