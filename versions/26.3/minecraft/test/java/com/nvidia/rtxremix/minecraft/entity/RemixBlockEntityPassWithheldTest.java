/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The extraction hook notes only a block entity with a level, so the rule is asked only of one. */
class RemixBlockEntityPassWithheldTest {

    @Test
    void aChestBehindAWallKeepsItsParts() {
        assertTrue(RemixBlockEntityPass.keepsWithheld(false, ChestBlockEntity.class));
        assertTrue(RemixBlockEntityPass.keepsWithheld(false, TrappedChestBlockEntity.class));
    }

    @Test
    void aBrokenChestRetires() {
        assertFalse(RemixBlockEntityPass.keepsWithheld(true, ChestBlockEntity.class));
    }

    @Test
    void aWithheldCopperBeaconRetiresSoItsHeadNeverStandsInItsBeam() {
        assertFalse(RemixBlockEntityPass.keepsWithheld(false, CopperBeaconBlockEntity.class));
        assertFalse(RemixBlockEntityPass.keepsWithheld(true, CopperBeaconBlockEntity.class));
    }
}
