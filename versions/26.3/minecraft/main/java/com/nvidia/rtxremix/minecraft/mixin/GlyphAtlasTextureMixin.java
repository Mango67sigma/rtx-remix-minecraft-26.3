/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives every glyph atlas page a {@link GlyphPageMirror} and brackets each glyph upload
 * so the command-encoder hook knows which page the pixels are for.
 *
 * <p>{@code FontTexture.add} is the one place a page is written: it finds a slot in the
 * page's {@code DynamicAtlasTree} and calls the glyph's {@code upload} with the slot and
 * the page texture. The glyph implementations reach the GPU through two different
 * encoder calls, {@code writeToTexture} for TrueType, Unihex and the built-in glyphs and
 * {@code copyBufferToTexture} for bitmap fonts, which is why the scope is opened here
 * and the pixels are read where the encoder receives them.
 *
 * <p>A page is coloured or grayscale for life: {@code GlyphStitcher} picks
 * {@code GlyphRenderTypes.createForColorTexture} for an RGBA8 page and
 * {@code createForGrayscaleTexture}, whose {@code IS_GRAYSCALE} text shader samples
 * {@code .rrrr}, for an R8 page, and {@code add} rejects a glyph of the other kind.
 */
@Mixin(FontTexture.class)
public abstract class GlyphAtlasTextureMixin implements GlyphPageMirror.Owner {

    @Shadow @Final private boolean colored;

    private GlyphPageMirror rtxremix$mirror;

    @Override
    public GlyphPageMirror rtxremix$glyphPageMirror() {
        // Created on first use: colored is assigned by the constructor, so a field
        // initialiser could not read it.
        if (rtxremix$mirror == null) {
            rtxremix$mirror = new GlyphPageMirror(!colored);
        }
        return rtxremix$mirror;
    }

    @WrapOperation(
        method = "add",
        at = @At(value = "INVOKE",
                target = "Lcom/mojang/blaze3d/font/GlyphBitmap;upload(IILcom/mojang/renderpearl/api/textures/GpuTexture;)V")
    )
    private void rtxremix$mirrorGlyphUpload(GlyphBitmap glyph, int x, int y, GpuTexture page,
                                            Operation<Void> original) {
        GlyphPageMirror.beginUpload(rtxremix$glyphPageMirror(), page);
        try {
            original.call(glyph, x, y, page);
        } finally {
            GlyphPageMirror.endUpload();
        }
    }
}
