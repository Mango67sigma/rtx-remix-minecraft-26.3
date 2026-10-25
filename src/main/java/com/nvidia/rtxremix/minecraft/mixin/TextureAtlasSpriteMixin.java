/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.nvidia.rtxremix.minecraft.texture.AnimatedSpriteBinding;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Binds every animation state an atlas creates to the sprite it animates.
 *
 * <p>{@code TextureAtlas.upload} creates one {@code AnimationState} per animated sprite
 * through {@code TextureAtlasSprite.createAnimationState}, the last point where the
 * sprite, and with it the atlas slot, is in hand. The slot origin {@code x/y} is the corner
 * of the stitcher's padded slot; the content, which the sprite's UVs sample and which Remix's
 * atlas copy holds, starts {@code padding} texels further in, so that is what is bound. See {@link AnimatedSpriteBinding}.
 */
@Mixin(TextureAtlasSprite.class)
public abstract class TextureAtlasSpriteMixin {

    @Shadow @Final private int x;
    @Shadow @Final private int y;
    @Shadow @Final private int padding;

    @Inject(method = "createAnimationState", at = @At("RETURN"))
    private void rtxremix$bindAnimationState(GpuBufferSlice uboSlice, int spriteUboSize,
                                             CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
        SpriteContents.AnimationState state = cir.getReturnValue();
        if (state != null) {
            ((AnimatedSpriteBinding) (Object) state).rtxremix$bindSprite(
                    (TextureAtlasSprite) (Object) this, x + padding, y + padding);
        }
    }
}
