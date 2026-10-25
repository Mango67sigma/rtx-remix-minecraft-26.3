/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The transient-block capture follows the entity pass's frame: without an open frame the
 * submit stays vanilla's, and the pass starts every frame with no tokens held over for
 * positions vanilla stopped submitting. The capture itself tesselates through the game's
 * model manager and is verified in game.
 */
class RemixTransientBlockPassTest {

    @Test
    void withoutAnOpenEntityFrameVanillaKeepsTheSubmit() {
        RemixEntityPass pass = new RemixEntityPass(id -> false);
        // The state is never read when the frame is closed.
        assertFalse(pass.captureTransientBlock(null));
    }

    @Test
    void aFrameStartsWithNoTokensForUnsubmittedPositions() {
        RemixTransientBlockPass pass = new RemixTransientBlockPass();
        pass.beginFrame();
        pass.beginFrame();
        assertEquals(0, pass.ownerCount());
    }

    @Test
    void aPositionKeepsItsTokenWhileSubmittedAndLosesItAFrameAfterItStops() {
        RemixBlockDamagePass.PositionOwners owners = new RemixBlockDamagePass.PositionOwners();
        owners.endFrame();
        Object first = owners.ownerFor(42L);
        owners.endFrame();
        assertEquals(first, owners.ownerFor(42L), "same token while submitted every frame");
        owners.endFrame();
        owners.endFrame();
        assertEquals(0, owners.size(), "a frame without the position drops its token");
    }
}
