/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies a glyph upload into its page mirror where the command encoder hands it to the
 * graphics backend.
 *
 * <p>Since 1.21.5 every texture upload goes through {@code CommandEncoder}, and the
 * frontend encoder is the one every backend sits behind: {@code GpuDevice.createCommandEncoder}
 * returns the {@code FrontendCommandEncoder} that {@code FrontendGpuDevice} wraps around
 * the OpenGL or Vulkan backend. Hooking it rather than {@code GlCommandEncoder} keeps the
 * mirror working on either backend.
 *
 * <p>Two encoder calls reach a glyph page. {@code writeToTexture(GpuTexture, NativeImage, ...)}
 * validates and forwards to the {@code ByteBuffer} overload, which is what TrueType and
 * built-in glyphs use through the image and Unihex glyphs use directly, so the
 * {@code ByteBuffer} overload sees both; its source is tightly packed at the texture
 * format's texel size. Bitmap-font glyphs instead copy a window of the font sheet from a
 * GPU staging buffer with {@code copyBufferToTexture}; the sheet's CPU address comes from
 * {@code BitmapProviderImageDataHolderMixin}, offered while the same glyph upload builds
 * the buffer.
 *
 * <p>Each hook sits on the backend call, after the frontend's validation, so a rejected
 * write never reaches the mirror. Outside a glyph upload scope, or for a write into any
 * texture other than the scope's page, each costs one static read.
 */
@Mixin(FrontendCommandEncoder.class)
public abstract class FrontendCommandEncoderMixin {

    @Inject(
        method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
        at = @At(value = "INVOKE",
                target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V")
    )
    private void rtxremix$mirrorGlyphWrite(GpuTexture destination, ByteBuffer source, int mipLevel,
                                           int depthOrLayer, int destX, int destY, int width, int height,
                                           CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget(destination);
        if (page == null || mipLevel != 0 || width <= 0 || height <= 0) {
            return;
        }
        int texel = destination.getFormat().blockSize();
        page.blit(destX, destY, width, height, texel, source, width * texel);
    }

    @Inject(
        method = "copyBufferToTexture(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;IIIILcom/mojang/renderpearl/api/textures/GpuTexture;IIIIII)V",
        at = @At(value = "INVOKE",
                target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;copyBufferToTexture(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;IIIILcom/mojang/renderpearl/api/textures/GpuTexture;IIIIII)V")
    )
    private void rtxremix$mirrorGlyphCopy(GpuBufferSlice source, int sourceX, int sourceY,
                                          int sourceWidth, int sourceHeight, GpuTexture destination,
                                          int destinationX, int destinationY, int copyWidth, int copyHeight,
                                          int mipLevel, int arrayLayer, CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget(destination);
        if (page == null || mipLevel != 0 || copyWidth <= 0 || copyHeight <= 0) {
            return;
        }
        long base = GlyphPageMirror.stagingAddress(source.buffer());
        if (base == 0L) {
            return;
        }
        // The backend reads the buffer as rows of sourceWidth texels of the destination's
        // format, starting sourceX texels into row sourceY of the slice.
        int texel = destination.getFormat().blockSize();
        long rowPitch = (long) sourceWidth * texel;
        long first = source.offset() + ((long) sourceY * sourceWidth + sourceX) * texel;
        long span = (copyHeight - 1) * rowPitch + (long) copyWidth * texel;
        if (first < 0L || first + span > GlyphPageMirror.stagingSize() || span > Integer.MAX_VALUE) {
            return;
        }
        page.blit(destinationX, destinationY, copyWidth, copyHeight, texel,
                MemoryUtil.memByteBuffer(base + first, (int) span), (int) rowPitch);
    }
}
