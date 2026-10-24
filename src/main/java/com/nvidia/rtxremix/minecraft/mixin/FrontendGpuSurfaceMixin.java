/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Redirects the window surface's present to Remix's present, so Vulkan owns the
 * swapchain whenever the renderer is initialized. Before that, and if
 * initialization fails, the backend's own present (the GL buffer swap) runs
 * unchanged.
 *
 * <p>The wrap sits inside {@code FrontendGpuSurface.present}, around the call to
 * the backend, so the frontend's acquired/blitted bookkeeping still flips and the
 * next frame's {@code acquireNextTexture} is accepted. On the GL backend the
 * replaced call is {@code SDL_GL_SwapWindow}: the same point the 1.21.1 port
 * redirected {@code glfwSwapBuffers} in {@code RenderSystem.flipFrame}. The
 * composited GL frame has already been blitted to the default framebuffer by
 * {@code blitFromTexture}, which is where {@link RemixRenderer#presentFrameVk()}
 * copies it from. While RTX is switched off the renderer still presents, which is
 * what keeps the GL-Vulkan semaphore pair in step across the toggle.
 */
@Mixin(FrontendGpuSurface.class)
public abstract class FrontendGpuSurfaceMixin {

    @WrapOperation(
            method = "present",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/api/GpuSurfaceBackend;present()V"
            ))
    private void rtxremix$presentThroughRemix(GpuSurfaceBackend backend, Operation<Void> original) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.presentFrameVk();
        } else {
            original.call(backend);
        }
    }
}
