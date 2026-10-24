/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
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
 * {@code SpriteContents.upload}.
 */
public interface AnimatedSpriteBinding {

    void rtxremix$bindSprite(TextureAtlasSprite sprite);
}
