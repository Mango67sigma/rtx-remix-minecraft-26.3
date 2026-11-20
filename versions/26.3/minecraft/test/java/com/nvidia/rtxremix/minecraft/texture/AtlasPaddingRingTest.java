/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Since 1.21.11 vanilla's stitch draws each sprite over its whole padded slot with a
 * clamp-to-edge sampler, so the padding ring holds replicated edge texels. The mod's
 * atlas copies must hold the same ring, or Remix's mips average sprite edges with black.
 */
class AtlasPaddingRingTest {

    // A 7x7 slot at the origin, content at (2, 2) 3x3, padding 2; a neighbouring slot
    // starts at column 7 and must stay untouched.
    private static final int ATLAS_W = 9;
    private static final int ATLAS_H = 7;
    private static final int CX = 2;
    private static final int CY = 2;
    private static final int CW = 3;
    private static final int CH = 3;
    private static final int PADDING = 2;
    private static final byte UNWRITTEN = (byte) 0xEE;

    @Test
    void ringReplicatesEdgesAndCornersInAnR8Layer() {
        assertRing(1);
    }

    @Test
    void ringReplicatesEdgesAndCornersInAnRgbaLayer() {
        assertRing(4);
    }

    @Test
    void zeroPaddingWritesNothing() {
        byte[] atlas = syntheticAtlas(4);
        byte[] before = atlas.clone();
        LabPBRConverter.fillPaddingRing(atlas, ATLAS_W, ATLAS_H, 4, CX, CY, CW, CH, 0);
        assertArrayEquals(before, atlas);
    }

    @Test
    void ringIsClippedToTheBuffer() {
        // Content at (1, 1) of a 4x4 buffer with padding 2: only one texel of ring fits on
        // the top and left, and none past the right and bottom edges beyond the buffer.
        int w = 4, h = 4;
        byte[] atlas = new byte[w * h];
        java.util.Arrays.fill(atlas, UNWRITTEN);
        atlas[1 * w + 1] = 10; atlas[1 * w + 2] = 11;
        atlas[2 * w + 1] = 12; atlas[2 * w + 2] = 13;
        LabPBRConverter.fillPaddingRing(atlas, w, h, 1, 1, 1, 2, 2, 2);
        byte[] expected = {
            10, 10, 11, 11,
            10, 10, 11, 11,
            12, 12, 13, 13,
            12, 12, 13, 13,
        };
        assertArrayEquals(expected, atlas);
    }

    @Test
    void contentOutsideTheBufferWritesNothing() {
        byte[] atlas = syntheticAtlas(1);
        byte[] before = atlas.clone();
        LabPBRConverter.fillPaddingRing(atlas, ATLAS_W, ATLAS_H, 1, 7, 5, 3, 3, 2);
        assertArrayEquals(before, atlas);
    }

    private static void assertRing(int bpp) {
        byte[] atlas = syntheticAtlas(bpp);
        LabPBRConverter.fillPaddingRing(atlas, ATLAS_W, ATLAS_H, bpp, CX, CY, CW, CH, PADDING);

        int slotW = CW + 2 * PADDING;
        int slotH = CH + 2 * PADDING;
        for (int y = 0; y < ATLAS_H; y++) {
            for (int x = 0; x < ATLAS_W; x++) {
                for (int c = 0; c < bpp; c++) {
                    byte actual = atlas[(y * ATLAS_W + x) * bpp + c];
                    byte expected;
                    if (x >= slotW || y >= slotH) {
                        expected = UNWRITTEN; // the neighbouring slot
                    } else {
                        // What a clamp-to-edge sampler reads for this slot texel.
                        int sx = Math.max(CX, Math.min(CX + CW - 1, x));
                        int sy = Math.max(CY, Math.min(CY + CH - 1, y));
                        expected = contentValue(sx - CX, sy - CY, c);
                    }
                    assertEquals(expected, actual, "texel (" + x + ", " + y + ") channel " + c);
                }
            }
        }

        // The four corners each take their corner texel, spelled out.
        assertEquals(contentValue(0, 0, 0), atlas[0]);
        assertEquals(contentValue(CW - 1, 0, 0), atlas[(slotW - 1) * bpp]);
        assertEquals(contentValue(0, CH - 1, 0), atlas[((slotH - 1) * ATLAS_W) * bpp]);
        assertEquals(contentValue(CW - 1, CH - 1, 0), atlas[((slotH - 1) * ATLAS_W + slotW - 1) * bpp]);
    }

    /** A buffer whose content texels are distinct per texel and channel; the rest unwritten. */
    private static byte[] syntheticAtlas(int bpp) {
        byte[] atlas = new byte[ATLAS_W * ATLAS_H * bpp];
        java.util.Arrays.fill(atlas, UNWRITTEN);
        for (int y = 0; y < CH; y++)
            for (int x = 0; x < CW; x++)
                for (int c = 0; c < bpp; c++)
                    atlas[((CY + y) * ATLAS_W + CX + x) * bpp + c] = contentValue(x, y, c);
        return atlas;
    }

    private static byte contentValue(int x, int y, int channel) {
        return (byte) (1 + y * 16 + x * 4 + channel);
    }
}
