/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

/**
 * The LOD mod whose terrain {@link LodTerrain} draws: what the renderer asks of it beyond
 * the sections its hooks publish. Render thread only.
 */
public interface LodSource {

    /**
     * The mod stops drawing its LOD terrain and baking face shading into it, and sends
     * every section again so the capture receives the ones it built earlier.
     */
    void startCapture();

    /**
     * Called every captured frame. Finishes whatever {@link #startCapture} could not do yet,
     * such as before the mod has loaded a world.
     */
    void update();

    /** The mod draws its own LOD terrain again and rebuilds it with its own buffers. */
    void stopCapture();

    /** The radius the mod builds LOD terrain to, in chunks, or 0 before it has started. */
    int radiusChunks();

    /** The mod and its version, for the log. */
    String describe();
}
