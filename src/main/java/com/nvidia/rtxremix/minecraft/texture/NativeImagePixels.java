/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.ARGB;

/**
 * Reads {@link NativeImage} pixels in the packing the texture code is written against.
 *
 * <p>{@code NativeImage.getPixel} returns ARGB, with red in bits 16 to 23. The converters
 * here index channels by their byte position in an RGBA image's memory, which as a
 * little-endian int is ABGR: red in the low byte, alpha in the high byte. That is what
 * {@code getPixelRGBA} returned in 1.21.1 and what the private {@code getPixelABGR}
 * still reads, so swapping red and blue back gives the same int bit for bit, including
 * the format and bounds checks {@code getPixel} applies.
 */
final class NativeImagePixels {

    /** The pixel at ({@code x}, {@code y}) as ABGR: red in the low byte, alpha in the high byte. */
    static int abgr(NativeImage image, int x, int y) {
        return ARGB.toABGR(image.getPixel(x, y));
    }

    private NativeImagePixels() {}
}
