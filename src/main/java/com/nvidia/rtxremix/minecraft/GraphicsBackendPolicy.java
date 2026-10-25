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
 * OpenGL backend. Minecraft 26.3 takes the backend from a launch argument
 * ({@code --graphicsBackend}) when one is given, otherwise from the in-game
 * Options &gt; Video Settings &gt; Graphics API preference. Only that preference's
 * Vulkan value tries Vulkan first ({@code PreferredGraphicsApi.getBackendsToTry}
 * returns {vulkan, gl} for {@code VULKAN} and {gl, vulkan} for {@code DEFAULT} and
 * {@code OPENGL}); with either of the other two OpenGL comes up unless it fails.
 *
 * <p>When the launcher passed no backend, {@code MinecraftClientMixin} supplies
 * {@code OPENGL} as if it had, so the force changes the outcome only for a player
 * who chose Vulkan in-game. Vanilla then logs that the backend was forced by
 * launch argument and the in-game setting is ignored; the setting itself stays
 * selectable in Video Settings and keeps its stored value, but has no effect while
 * the force applies. The renderer logs the preference it overrode. {@code
 * -Drtxremix.forceOpenGl=false} leaves the choice to the launcher and the in-game
 * setting, in which case the renderer refuses to initialize on Vulkan.
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
