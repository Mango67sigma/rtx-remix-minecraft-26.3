/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the glyph page mirror where the CPU copy of a bitmap font's staging buffer is.
 *
 * <p>A bitmap glyph's {@code upload} copies its window of the font sheet into the page
 * with {@code copyBufferToTexture}, reading from a GPU buffer that {@code gpuData()}
 * fills from the sheet on first use. The sheet image is held beside the buffer until the
 * provider closes, and the buffer is a byte-for-byte copy of it, so the image's pixels
 * are what the copy reads. {@code gpuData()} is evaluated inside the glyph's
 * {@code upload}, so the offer lands inside the upload scope that
 * {@code GlyphAtlasTextureMixin} opened and is dropped when it closes.
 */
@Mixin(BitmapProvider.ImageDataHolder.class)
public abstract class BitmapProviderImageDataHolderMixin {

    @Shadow @Final private NativeImage image;

    @Inject(method = "gpuData", at = @At("RETURN"))
    private void rtxremix$offerSheetToGlyphMirror(CallbackInfoReturnable<GpuBuffer> cir) {
        if (GlyphPageMirror.uploadTarget() == null) {
            return;
        }
        long size = (long) image.getWidth() * image.getHeight() * image.format().components();
        GlyphPageMirror.offerStagingSource(cir.getReturnValue(), image.getPointer(), size);
    }
}
