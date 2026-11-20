/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Since the GPU-stitched atlases, {@code TextureAtlasSprite.getX()/getY()} is the origin
 * of a sprite's padded slot and its content, which its UVs sample, starts {@code padding}
 * texels further in. Every atlas copy must land where the UVs read.
 */
class BlockTextureManagerSpriteOriginTest {

    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/test.png");

    @Test
    void contentOriginSkipsTheStitcherPadding() {
        // The block atlas at four mip levels pads by 1 << 4 = 16 texels.
        TextureAtlasSprite sprite = sprite(48, 96, 16, 1024, 512, 16);
        assertEquals(48, sprite.getX());
        assertEquals(64, BlockTextureManager.spriteContentX(sprite, 1024));
        assertEquals(112, BlockTextureManager.spriteContentY(sprite, 512));
    }

    @Test
    void contentOriginWithTheMinimumPadding() {
        // The item and particle atlases have no mip levels; the stitcher still pads by one.
        TextureAtlasSprite sprite = sprite(18, 0, 16, 512, 256, 1);
        assertEquals(19, BlockTextureManager.spriteContentX(sprite, 512));
        assertEquals(1, BlockTextureManager.spriteContentY(sprite, 256));
    }

    @Test
    void spritePaddingIsTheContentOriginMinusTheSlotCorner() {
        TextureAtlasSprite padded = sprite(48, 96, 16, 1024, 512, 2);
        assertEquals(2, BlockTextureManager.spritePadding(padded,
                BlockTextureManager.spriteContentX(padded, 1024),
                BlockTextureManager.spriteContentY(padded, 512)));
        TextureAtlasSprite unpadded = sprite(32, 48, 16, 256, 256, 0);
        assertEquals(0, BlockTextureManager.spritePadding(unpadded,
                BlockTextureManager.spriteContentX(unpadded, 256),
                BlockTextureManager.spriteContentY(unpadded, 256)));
    }

    @Test
    void spritePaddingFallsBackToTheSmallerAxisAndIsNeverNegative() {
        TextureAtlasSprite sprite = sprite(48, 96, 16, 1024, 512, 0);
        assertEquals(1, BlockTextureManager.spritePadding(sprite, 51, 97));
        assertEquals(0, BlockTextureManager.spritePadding(sprite, 47, 98));
    }

    @Test
    void contentOriginIsTheSlotOriginWithoutPadding() {
        TextureAtlasSprite sprite = sprite(32, 48, 16, 256, 256, 0);
        assertEquals(sprite.getX(), BlockTextureManager.spriteContentX(sprite, 256));
        assertEquals(sprite.getY(), BlockTextureManager.spriteContentY(sprite, 256));
    }

    @Test
    void texelOriginRoundTripsEveryTexelOfEveryAtlasWidthUpTo2048() {
        for (int size = 1; size <= 2048; size++) {
            for (int texel = 0; texel <= size; texel++) {
                float uv = (float) texel / size;
                if (BlockTextureManager.texelOrigin(uv, size) != texel) {
                    throw new AssertionError("texel " + texel + " of " + size + " recovered as "
                            + BlockTextureManager.texelOrigin(uv, size));
                }
            }
        }
    }

    @Test
    void texelOriginRoundTripsEveryTexelOfTheLargestAtlases() {
        for (int size : new int[] {4096, 8192, 12288, 16384, 32768}) {
            for (int texel = 0; texel <= size; texel++) {
                float uv = (float) texel / size;
                if (BlockTextureManager.texelOrigin(uv, size) != texel) {
                    throw new AssertionError("texel " + texel + " of " + size + " recovered as "
                            + BlockTextureManager.texelOrigin(uv, size));
                }
            }
        }
    }

    @Test
    void contentFitsOnlyInsideTheAtlas() {
        assertTrue(BlockTextureManager.contentFits(0, 0, 16, 16, 16, 16));
        assertTrue(BlockTextureManager.contentFits(16, 32, 16, 16, 64, 64));
        assertFalse(BlockTextureManager.contentFits(49, 0, 16, 16, 64, 64));
        assertFalse(BlockTextureManager.contentFits(0, 49, 16, 16, 64, 64));
        assertFalse(BlockTextureManager.contentFits(-1, 0, 16, 16, 64, 64));
        assertFalse(BlockTextureManager.contentFits(0, -1, 16, 16, 64, 64));
    }

    private static TextureAtlasSprite sprite(int x, int y, int size, int atlasW, int atlasH, int padding) {
        SpriteContents contents = new SpriteContents(
                Identifier.fromNamespaceAndPath("minecraft", "test/" + x + "_" + y),
                new FrameSize(size, size),
                new NativeImage(size, size, false));
        // The constructor is protected; an anonymous subclass reaches it.
        TextureAtlasSprite sprite = new TextureAtlasSprite(ATLAS, contents, atlasW, atlasH, x, y, padding) {};
        contents.close();
        return sprite;
    }
}
