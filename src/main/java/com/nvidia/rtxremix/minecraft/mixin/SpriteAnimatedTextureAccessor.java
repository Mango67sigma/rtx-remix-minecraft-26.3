/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The flipbook layout of an animated sprite, which {@code SpriteAnimationStateMixin} needs to find a frame. */
@Mixin(SpriteContents.AnimatedTexture.class)
public interface SpriteAnimatedTextureAccessor {

    /** Frames per row of the sprite's sheet. */
    @Accessor("frameRowSize")
    int rtxremix$frameRowSize();

    /** Whether the animation blends each frame into the next between frame changes. */
    @Accessor("interpolateFrames")
    boolean rtxremix$interpolateFrames();
}
