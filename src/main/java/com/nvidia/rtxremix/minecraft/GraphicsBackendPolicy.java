/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

/**
 * The mod's position on Minecraft's graphics backend.
 *
 * <p>Remix reaches the screen through the GL-to-Vulkan interop ({@code
 * EXT_memory_object}, {@code EXT_semaphore}), so the renderer only works on the
 * OpenGL backend. Minecraft 26.3 picks Vulkan by default where it is available
 * and lets a launch argument ({@code --graphicsBackend OPENGL}) force a backend.
 * The mod supplies that force itself from {@code MinecraftClientMixin} when the
 * launcher did not, so an install started from any launcher comes up on OpenGL;
 * {@code -Drtxremix.forceOpenGl=false} leaves the choice to the launcher and the
 * in-game setting, in which case the renderer refuses to initialize on Vulkan.
 */
public final class GraphicsBackendPolicy {

    /** Launch property that disables the mod's own backend force. */
    public static final String FORCE_PROPERTY = "rtxremix.forceOpenGl";

    /** Read once: the backend is chosen in Minecraft's constructor. */
    public static final boolean FORCE_OPENGL =
            Boolean.parseBoolean(System.getProperty(FORCE_PROPERTY, "true"));

    /** {@code DeviceInfo.backendName()} of the OpenGL device ({@code GlHeuristics.createDeviceInfo}). */
    public static final String OPENGL_BACKEND_NAME = "OpenGL";

    private GraphicsBackendPolicy() {
    }

    /** @return whether the active device is the OpenGL backend the interop needs */
    public static boolean isOpenGl(String backendName) {
        return OPENGL_BACKEND_NAME.equals(backendName);
    }

    /** The log line for a renderer that cannot come up because another backend is active. */
    public static String refusalMessage(String backendName) {
        return "RTX Remix needs the OpenGL graphics backend, but Minecraft is running on " + backendName
                + ". Start the game with --graphicsBackend OPENGL, or set Options > Video Settings >"
                + " Graphics API to OpenGL and restart. The mod forces OpenGL itself unless -D"
                + FORCE_PROPERTY + "=false is set.";
    }
}
