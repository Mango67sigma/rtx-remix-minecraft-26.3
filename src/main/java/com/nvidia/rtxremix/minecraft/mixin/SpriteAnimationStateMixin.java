/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.AnimatedSpriteBinding;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards each animated block-atlas sprite's frame changes to Remix as the same patches
 * 1.21.1's CPU upload produced.
 *
 * <p>1.21.1 animated an atlas on the CPU: {@code SpriteContents.Ticker.tickAndUpload}
 * uploaded the new frame's window of the sprite sheet when the frame index changed, and
 * between frame changes an interpolated sprite blended the current frame into the next
 * into a sprite-sized image and uploaded that. {@code SpriteContentsUploadMixin} read
 * both at {@code SpriteContents.upload}. Since 1.21.11 the atlas animates on the GPU:
 * {@code AnimationState.tick} only advances the frame counters, and
 * {@code drawToAtlas} draws the frame, or the shader's blend of two frames, from
 * per-frame textures, so no CPU copy of an animated frame exists.
 *
 * <p>This hook runs after {@code tick}, once per tick per sprite inside
 * {@code TextureAtlas.cycleAnimationFrames}, and reproduces the 1.21.1 stream from the
 * state's counters: a raw frame patch pointing into the sheet when the tick moved to a
 * frame with a different index, and otherwise, for an interpolated sprite whose current
 * and next frames differ, a patch of the two frames blended with 1.21.1's weights into a
 * buffer this state owns. The blend runs only when the forwarder will take the patch,
 * which is the cost vanilla itself paid in 1.21.1 for every interpolated sprite.
 */
@Mixin(SpriteContents.AnimationState.class)
public abstract class SpriteAnimationStateMixin implements AnimatedSpriteBinding {

    @Shadow private int frame;
    @Shadow private int subFrame;
    @Shadow @Final private SpriteContents.AnimatedTexture animationInfo;
    @Shadow private boolean isDirty;

    @Unique private TextureAtlasSprite rtxremix$sprite;
    /** Atlas texel of the sprite's first content texel (past the stitcher padding). */
    @Unique private int rtxremix$contentX;
    @Unique private int rtxremix$contentY;
    /** Sprite-sized RGBA buffer for blended frames, allocated on first use and freed on close. */
    @Unique private long rtxremix$blend;

    @Override
    public void rtxremix$bindSprite(TextureAtlasSprite sprite, int contentX, int contentY) {
        rtxremix$sprite = sprite;
        rtxremix$contentX = contentX;
        rtxremix$contentY = contentY;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$forwardFrame(CallbackInfo ci) {
        TextureAtlasSprite sprite = rtxremix$sprite;
        if (sprite == null) {
            return;
        }
        SpriteContents contents = sprite.contents();
        Identifier id = contents.name();
        if (!AtlasAnimationForwarder.acceptsPatch(id)) {
            return;
        }
        NativeImage sheet = ((SpriteContentsAccessor) contents).getImage();
        SpriteAnimatedTextureAccessor layout = (SpriteAnimatedTextureAccessor) (Object) animationInfo;
        List<SpriteContents.FrameInfo> frames = animationInfo.frames;
        int width = contents.width();
        int height = contents.height();
        if (subFrame == 0) {
            // The tick moved to the next frame; isDirty says its index changed.
            if (isDirty) {
                AtlasAnimationForwarder.addFramePatch(id, rtxremix$contentX, rtxremix$contentY, width, height,
                        sheet, layout.rtxremix$frameRowSize(), frames.get(frame).index());
            }
            return;
        }
        if (!layout.rtxremix$interpolateFrames()) {
            return;
        }
        SpriteContents.FrameInfo current = frames.get(frame);
        int nextIndex = frames.get((frame + 1) % frames.size()).index();
        if (current.index() == nextIndex) {
            return;
        }
        if (rtxremix$blend == 0L) {
            rtxremix$blend = MemoryUtil.nmemAlloc((long) width * height * 4);
            if (rtxremix$blend == 0L) {
                return;
            }
        }
        double currentWeight = 1.0 - (double) subFrame / current.time();
        AtlasAnimationForwarder.addBlendedPatch(id, rtxremix$contentX, rtxremix$contentY, width, height,
                sheet, layout.rtxremix$frameRowSize(), current.index(), nextIndex, currentWeight,
                rtxremix$blend);
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void rtxremix$freeBlend(CallbackInfo ci) {
        if (rtxremix$blend != 0L) {
            MemoryUtil.nmemFree(rtxremix$blend);
            rtxremix$blend = 0L;
        }
    }
}
