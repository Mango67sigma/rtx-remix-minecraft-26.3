/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixBlockEntityPassWithheldTest {

    @ParameterizedTest
    @CsvSource({
            "true, false, false, true",
            "false, false, false, false",
            "true, true, false, false",
            "false, true, false, false",
            "true, false, true, false",
            "true, true, true, false"
    })
    void aWithheldBlockEntityKeepsItsPartsOnlyWhileLiveAndNotSteeringALight(
            boolean hasLevel, boolean removed, boolean posesLight, boolean expected) {
        assertEquals(expected, RemixBlockEntityPass.keepsWithheld(hasLevel, removed, posesLight));
    }

    @Test
    void aChestBehindAWallStaysAndABrokenOneGoes() {
        assertTrue(RemixBlockEntityPass.keepsWithheld(true, false, false));
        assertFalse(RemixBlockEntityPass.keepsWithheld(true, true, false));
    }

    @Test
    void aWithheldCopperBeaconRetiresSoItsHeadNeverStandsInItsBeam() {
        assertFalse(RemixBlockEntityPass.keepsWithheld(true, false, true));
    }
}
