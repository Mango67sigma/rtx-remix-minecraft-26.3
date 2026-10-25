/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.TexturePatch;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
            AtlasAnimationForwarder.addPatch(guiSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16, 0);
            assertEquals(0, pendingPatchCount());

            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16, 0);
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
            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16, 0);
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

    /**
     * The animation hook binds {@code getX() + padding} (the slot's content origin, which
     * is where the sprite's UVs and Remix's atlas copy put the content) and the forwarder
     * must queue the patch there, not at the padded slot's corner.
     */
    @Test
    void framePatchOfAPaddedSpriteLandsAtTheContentOrigin() throws Exception {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        int padding = 16;
        Identifier atlasId = BlockTextureManager.BLOCK_ATLAS_ID;
        SpriteContents contents = new SpriteContents(blockSprite, new FrameSize(4, 4), new NativeImage(4, 8, false));
        TextureAtlasSprite sprite = new TextureAtlasSprite(atlasId, contents, 256, 128, 48, 32, padding) {};
        int contentX = sprite.getX() + padding;
        int contentY = sprite.getY() + padding;
        assertEquals(contentX, BlockTextureManager.spriteContentX(sprite, 256));
        assertEquals(contentY, BlockTextureManager.spriteContentY(sprite, 128));

        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(atlasId);
        try (NativeImage sheet = new NativeImage(4, 8, false)) {
            AtlasAnimationForwarder.addFramePatch(blockSprite, contentX, contentY, 4, 4, padding, sheet, 1, 1);
            List<?> patches = pendingPatches();
            assertEquals(1, patches.size());
            Object patch = patches.get(0);
            assertEquals(64, intField(patch, "dstX"));
            assertEquals(48, intField(patch, "dstY"));
            assertEquals(0, intField(patch, "srcX"));
            assertEquals(4, intField(patch, "srcY"));
            assertEquals(padding, intField(patch, "padding"));
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
            contents.close();
        }
    }

    /**
     * Vanilla's animation draw covers the whole padded slot, so the ring of replicated
     * edges follows each frame. The patch sent to Remix must start at the slot corner and
     * span the content plus the ring on every side, for RGBA and R8 layers alike.
     */
    @Test
    void flushedPatchCoversTheContentAndItsPaddingRing() throws Exception {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(BlockTextureManager.BLOCK_ATLAS_ID);
        try {
            // Content at (66, 50), 16x16, padding 2: the slot corner is (64, 48).
            AtlasAnimationForwarder.addPatch(blockSprite, 66, 50, 0, 16, 16, 16, 16, 32, 0L, 64, 2);
            Object record = pendingPatches().get(0);
            Method createPatch = AtlasAnimationForwarder.class.getDeclaredMethod(
                    "createPatch", record.getClass(), ByteBuffer.class, int.class, int.class);
            createPatch.setAccessible(true);

            TexturePatch rgba = (TexturePatch) createPatch.invoke(null, record, null, 4, 0);
            assertEquals(64, rgba.dstX);
            assertEquals(48, rgba.dstY);
            assertEquals(20, rgba.width);
            assertEquals(20, rgba.height);
            assertEquals(20 * 4, rgba.rowPitch);

            TexturePatch r8 = (TexturePatch) createPatch.invoke(null, record, null, 1, 0);
            assertEquals(20, r8.width);
            assertEquals(20, r8.rowPitch);
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    @Test
    void paddingIsClampedSoTheRingNeverStartsOutsideTheAtlas() throws Exception {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(BlockTextureManager.BLOCK_ATLAS_ID);
        try {
            AtlasAnimationForwarder.addPatch(blockSprite, 1, 3, 0, 0, 4, 4, 4, 4, 0L, 16, 2);
            assertEquals(1, intField(pendingPatches().get(0), "padding"));
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    /** The staged patch holds the frame at (p, p) and its replicated edges around it. */
    @Test
    void padPatchReplicatesTheFramesEdgesIntoTheRing() {
        // A 2x2 R8 frame with padding 1 becomes 4x4.
        byte[] frame = {1, 2, 3, 4};
        byte[] out = new byte[16];
        AtlasAnimationForwarder.padPatch(frame, 2, 2, 1, 1, out);
        assertArrayEquals(new byte[] {
            1, 1, 2, 2,
            1, 1, 2, 2,
            3, 3, 4, 4,
            3, 3, 4, 4,
        }, out);

        // A 1x2 RGBA frame with padding 2 becomes 5x6; every texel is the clamped frame texel.
        byte[] rgba = {10, 11, 12, 13, 20, 21, 22, 23};
        byte[] padded = new byte[5 * 6 * 4];
        AtlasAnimationForwarder.padPatch(rgba, 1, 2, 4, 2, padded);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 5; x++) {
                int src = y < 3 ? 0 : 4;
                for (int c = 0; c < 4; c++) {
                    assertEquals(rgba[src + c], padded[(y * 5 + x) * 4 + c], "(" + x + ", " + y + ")");
                }
            }
        }
    }

    private static List<?> pendingPatches() throws Exception {
        Field pendingPatches = AtlasAnimationForwarder.class.getDeclaredField("pendingPatches");
        pendingPatches.setAccessible(true);
        return (List<?>) pendingPatches.get(null);
    }

    private static int intField(Object patch, String name) throws Exception {
        Field f = patch.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(patch);
    }

    private static int pendingPatchCount() throws Exception {
        Field pendingPatches = AtlasAnimationForwarder.class.getDeclaredField("pendingPatches");
        pendingPatches.setAccessible(true);
        return ((List<?>) pendingPatches.get(null)).size();
    }
}
