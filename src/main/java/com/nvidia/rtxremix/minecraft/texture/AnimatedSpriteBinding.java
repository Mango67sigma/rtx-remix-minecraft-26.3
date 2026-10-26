/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Implemented by {@code SpriteContents.AnimationState} so a ticking animation knows the
 * atlas sprite it draws into.
 *
 * <p>Since 1.21.11 an atlas animates on the GPU: each {@code AnimationState} draws its
 * frame into the atlas from per-frame textures, and nothing on the CPU names the atlas
 * slot any more. {@code TextureAtlasSpriteMixin} binds the sprite when the atlas creates
 * the state, which gives the forwarder the destination rectangle 1.21.1 passed to
 * {@code SpriteContents.upload}. That rectangle starts at the sprite's content, not at
 * its padded slot ({@code getX()/getY()}), so the binding carries the content origin, and
 * the stitcher padding, because vanilla's draw also refreshes the ring of replicated edge
 * texels around the content and Remix's copy must follow it.
 */
public interface AnimatedSpriteBinding {

    /**
     * @param contentX atlas texel column of the sprite's first content texel, i.e. the
     *                 padded slot's {@code getX()} plus its stitcher padding
     * @param contentY the row counterpart of {@code contentX}
     * @param padding  the stitcher padding around the content, {@code contentX - getX()}
     */
    void rtxremix$bindSprite(TextureAtlasSprite sprite, int contentX, int contentY, int padding);
}
