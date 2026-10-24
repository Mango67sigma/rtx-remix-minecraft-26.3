/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasAnimationForwarderTest {

    @AfterEach
    void clearForwarder() {
        AtlasAnimationForwarder.clear();
    }

    @Test
    void addPatchIgnoresSpritesOutsideTheBlockAtlasAllowList() throws Exception {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        Identifier guiSprite = Identifier.fromNamespaceAndPath("minecraft", "gui/sprites/icon");

        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(BlockTextureManager.BLOCK_ATLAS_ID);

        try {
            AtlasAnimationForwarder.addPatch(guiSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16);
            assertEquals(0, pendingPatchCount());

            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16);
            assertEquals(1, pendingPatchCount());
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    @Test
    void addPatchIgnoresAllowedSpriteIdsFromOtherAtlases() throws Exception {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        Identifier particleAtlas = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/particles.png");

        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(particleAtlas);

        try {
            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16);
            assertEquals(0, pendingPatchCount());
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    @Test
    void acceptsPatchMatchesWhatAddPatchWouldQueue() {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        Identifier guiSprite = Identifier.fromNamespaceAndPath("minecraft", "gui/sprites/icon");
        Identifier particleAtlas = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/particles.png");

        assertFalse(AtlasAnimationForwarder.acceptsPatch(blockSprite), "inactive before init");
        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        assertFalse(AtlasAnimationForwarder.acceptsPatch(blockSprite), "outside an atlas tick");

        AtlasAnimationForwarder.beginAtlasUpload(particleAtlas);
        try {
            assertFalse(AtlasAnimationForwarder.acceptsPatch(blockSprite));
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }

        AtlasAnimationForwarder.beginAtlasUpload(BlockTextureManager.BLOCK_ATLAS_ID);
        try {
            assertTrue(AtlasAnimationForwarder.acceptsPatch(blockSprite));
            assertFalse(AtlasAnimationForwarder.acceptsPatch(guiSprite));
            AtlasAnimationForwarder.pause();
            assertFalse(AtlasAnimationForwarder.acceptsPatch(blockSprite));
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    /**
     * Pins 1.21.1's {@code InterpolationData} arithmetic, which the GPU animation of 26.3
     * no longer runs on the CPU: colour channels mix with the current frame's weight and
     * truncate, alpha is the current frame's.
     */
    @Test
    void blendFramesMixesColourAndKeepsTheCurrentFramesAlpha() {
        // A 2x1 sheet of 1x1 frames: frame 0 at x=0, frame 1 at x=1.
        long sheet = MemoryUtil.nmemAlloc(8);
        long out = MemoryUtil.nmemAlloc(4);
        try {
            byte[] frames = {(byte) 200, 100, 0, (byte) 255, 0, 50, (byte) 255, 10};
            for (int i = 0; i < frames.length; i++) {
                MemoryUtil.memPutByte(sheet + i, frames[i]);
            }

            AtlasAnimationForwarder.blendFrames(sheet, 2, 0, 0, 1, 0, 1, 1, 0.75, out);

            assertEquals((int) (0.75 * 200 + 0.25 * 0), MemoryUtil.memGetByte(out) & 0xFF);
            assertEquals((int) (0.75 * 100 + 0.25 * 50), MemoryUtil.memGetByte(out + 1) & 0xFF);
            assertEquals((int) (0.75 * 0 + 0.25 * 255), MemoryUtil.memGetByte(out + 2) & 0xFF);
            assertEquals(255, MemoryUtil.memGetByte(out + 3) & 0xFF);
        } finally {
            MemoryUtil.nmemFree(sheet);
            MemoryUtil.nmemFree(out);
        }
    }

    private static int pendingPatchCount() throws Exception {
        Field pendingPatches = AtlasAnimationForwarder.class.getDeclaredField("pendingPatches");
        pendingPatches.setAccessible(true);
        return ((List<?>) pendingPatches.get(null)).size();
    }
}
