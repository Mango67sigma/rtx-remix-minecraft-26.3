/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTexture;

/**
 * A {@link GlTexture} over a GL texture name the mod created and owns, so the
 * Remix interop images can be sampled through Minecraft's {@code RenderPass}
 * API like any other texture.
 *
 * <p>The GL backend binds a sampled texture through {@code GlTextureView.glId()},
 * which is all this wrapper has to provide. {@link #close()} is a no-op because
 * the backing memory belongs to {@link GlInterop}, which imports and deletes it;
 * the wrapper is dropped, never closed, when the interop image is recreated.
 * Requires the OpenGL backend, which the renderer refuses to initialize without.
 */
public final class ExternalGlTexture extends GlTexture {

    private ExternalGlTexture(String label, GpuFormat format, int width, int height, int glId) {
        // Sampling needs no frame-buffer cache: it is only consulted when the texture
        // is an attachment, and this wrapper is never one.
        super(GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                label, format, width, height, 1, 1, glId, null);
    }

    /**
     * Wraps {@code glId} and opens a view on it.
     *
     * @return the view to pass to {@code RenderPass.setUniform}; its texture is the wrapper
     */
    public static GpuTextureView wrap(String label, GpuFormat format, int width, int height, int glId) {
        ExternalGlTexture texture = new ExternalGlTexture(label, format, width, height, glId);
        return RenderSystem.getDevice().createTextureView(texture);
    }

    /** The GL texture belongs to the interop; nothing to release here. */
    @Override
    public void close() {
    }

    /** Releases a view from {@link #wrap} without touching the GL texture. */
    public static void release(GpuTextureView view) {
        if (view != null) {
            view.close();
        }
    }
}
