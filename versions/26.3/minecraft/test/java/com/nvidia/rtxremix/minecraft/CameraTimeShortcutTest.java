/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.mojang.blaze3d.platform.InputConstants;
import com.nvidia.rtxremix.minecraft.camera.CameraController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CameraTimeShortcutTest {

    /** SDL scancode of the keypad minus key, which InputConstants names no constant for. */
    private static final int KEY_KEYPAD_MINUS = 86;

    @Test
    void cameraTimeShortcutRecognizesEqualMinusAndFastModifier() {
        assertEquals(1, RtxRemixMod.cameraTimeAdjustmentDirection(InputConstants.KEY_EQUALS, 0));
        assertEquals(1, RtxRemixMod.cameraTimeAdjustmentDirection(
                InputConstants.KEY_EQUALS, InputConstants.MOD_SHIFT));
        assertEquals(-1, RtxRemixMod.cameraTimeAdjustmentDirection(InputConstants.KEY_MINUS, 0));
        assertEquals(0, RtxRemixMod.cameraTimeAdjustmentDirection(InputConstants.KEY_ADD, 0));
        assertEquals(0, RtxRemixMod.cameraTimeAdjustmentDirection(KEY_KEYPAD_MINUS, 0));
        assertEquals(10, RtxRemixMod.cameraTimeAdjustmentStep(0));
        assertEquals(250, RtxRemixMod.cameraTimeAdjustmentStep(InputConstants.MOD_CONTROL));
    }

    @Test
    void cameraTimeShortcutInactiveWithoutRecording() {
        assertFalse(RtxRemixMod.isCameraTimeShortcutActive(null));
        assertFalse(RtxRemixMod.isCameraTimeShortcutActive(new CameraController()));
    }
}
