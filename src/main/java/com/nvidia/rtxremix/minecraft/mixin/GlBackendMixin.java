/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Raises the OpenGL context the GL backend asks SDL for to what the Remix GL
 * interop needs.
 *
 * <p>{@code GlBackend.createWindow} sets the SDL GL attributes to a 3.3 core
 * profile right before every {@code SDL_CreateWindow}, including the hidden
 * utility window whose context ({@code SDL_GL_CreateContext} in {@code GlDevice})
 * every later window shares. Injecting after those attribute writes and before
 * the window is created replaces the version with 4.4: the interop imports
 * Remix's images and semaphores through {@code EXT_memory_object} and
 * {@code EXT_semaphore} and creates its textures with 4.5 direct state access,
 * which the 1.21.1 port obtained from the same hint on the GLFW window.
 */
@Mixin(GlBackend.class)
public abstract class GlBackendMixin {

    @Inject(
            method = "createWindow",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/sdl/SDLVideo;SDL_CreateWindow(Ljava/lang/CharSequence;IIJ)J",
                    remap = false
            ))
    private void rtxremix$requireOpenGl44(
            String title, int width, int height, long flags, CallbackInfoReturnable<Long> cir) {
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 4);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 4);
    }
}
