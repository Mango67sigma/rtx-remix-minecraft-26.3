/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelestialClockTest {

    @Test
    void clearSkyStarsAreOutAtNoonAndPeakAtMidnight() {
        assertEquals(0.0f, CelestialClock.clearSkyStarBrightness(0.0f), 0.0f);
        assertEquals(0.5f, CelestialClock.clearSkyStarBrightness(0.5f), 1.0e-6f);
    }

    @Test
    void clearSkyStarsMatchOneTwentyOneOneCurveAtDusk() {
        float skyAngle = 0.3f;
        float h = 1.0f - ((float) Math.cos(skyAngle * Math.PI * 2.0) * 2.0f + 0.25f);
        h = Math.max(0.0f, Math.min(1.0f, h));
        assertEquals(h * h * 0.5f, CelestialClock.clearSkyStarBrightness(skyAngle), 1.0e-6f);
        assertTrue(CelestialClock.clearSkyStarBrightness(skyAngle) > 0.0f);
    }

    @Test
    void clearSkyStarsArePeriodicInTheSkyAngle() {
        assertEquals(
                CelestialClock.clearSkyStarBrightness(0.4f),
                CelestialClock.clearSkyStarBrightness(1.4f),
                1.0e-5f);
    }
}
