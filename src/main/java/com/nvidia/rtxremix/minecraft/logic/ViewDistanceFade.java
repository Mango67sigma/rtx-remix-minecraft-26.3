/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

/**
 * Maps Minecraft's render distance onto a conf-layer strength: the shipped
 * 2-chunk endpoint profile blends over the 32-chunk endpoints in
 * {@code rtx.conf}. Manual mode ({@code /rtx viewdistance auto off}) holds the
 * strength at zero, which leaves the 32-chunk baseline showing. LOD terrain beyond
 * Minecraft's own pushes the band out to its radius through a second layer, whose endpoint
 * is the farthest LOD radius Distant Horizons offers; the scheme follows
 * {@code view_distance_lod.conf} in kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
public final class ViewDistanceFade {

    static final int MIN_RENDER_DISTANCE_CHUNKS = 2;
    static final int MAX_RENDER_DISTANCE_CHUNKS = 32;

    // Preserve the original 70%-to-80% fade-band calibration at every
    // supported Minecraft render distance.
    private static final float FADE_MIN_RATIO = 0.7f;
    private static final float FADE_MAX_RATIO = 0.8f;

    // Keep these endpoints aligned with packaging/rtx.conf and
    // confs/view_distance.conf respectively.
    static final float RESTORE_FADE_MIN = computeFadeMin(MAX_RENDER_DISTANCE_CHUNKS);
    static final float RESTORE_FADE_MAX = computeFadeMax(MAX_RENDER_DISTANCE_CHUNKS);
    static final float LAYER_FADE_MIN = computeFadeMin(MIN_RENDER_DISTANCE_CHUNKS);
    static final float LAYER_FADE_MAX = computeFadeMax(MIN_RENDER_DISTANCE_CHUNKS);
    // Distant Horizons' largest LOD radius; keep the endpoint in
    // confs/view_distance_lod.conf at the same ratios of it.
    static final int MAX_LOD_DISTANCE_CHUNKS = 4096;
    static final float LOD_LAYER_FADE_MIN = computeFadeMin(MAX_LOD_DISTANCE_CHUNKS);
    static final float LOD_LAYER_FADE_MAX = computeFadeMax(MAX_LOD_DISTANCE_CHUNKS);

    private boolean auto = true;
    private float displayedFadeMin = RESTORE_FADE_MIN;
    private float displayedFadeMax = RESTORE_FADE_MAX;

    static float computeFadeMin(int renderDistanceChunks) {
        return renderDistanceChunks * 16 * FADE_MIN_RATIO;
    }

    static float computeFadeMax(int renderDistanceChunks) {
        return renderDistanceChunks * 16 * FADE_MAX_RATIO;
    }

    /**
     * Strength of the 2-chunk endpoint over the 32-chunk baseline. Both
     * endpoint values are linear in chunk distance, so this one blend
     * reconstructs the exact 70%-to-80% band for every vanilla setting.
     */
    public float layerStrength(int renderDistanceChunks) {
        if (!auto) {
            displayedFadeMin = RESTORE_FADE_MIN;
            displayedFadeMax = RESTORE_FADE_MAX;
            return 0.0f;
        }
        int clamped = Math.clamp(renderDistanceChunks,
                MIN_RENDER_DISTANCE_CHUNKS, MAX_RENDER_DISTANCE_CHUNKS);
        displayedFadeMin = computeFadeMin(clamped);
        displayedFadeMax = computeFadeMax(clamped);
        return (float) (MAX_RENDER_DISTANCE_CHUNKS - clamped)
                / (MAX_RENDER_DISTANCE_CHUNKS - MIN_RENDER_DISTANCE_CHUNKS);
    }

    /**
     * Strength of the far LOD endpoint over the band {@link #layerStrength} left. The
     * runtime blends the two layers in turn, so the strength is the fraction of the way
     * from that band to the endpoint; both endpoints keep the 70%-to-80% ratios, so one
     * strength places both fade keys at the LOD radius. It is 0 without LOD terrain beyond
     * Minecraft's render distance. Call after {@link #layerStrength} for the same frame.
     *
     * @param lodRadiusBlocks radius of the LOD terrain drawn, 0 when there is none
     */
    public float lodLayerStrength(int renderDistanceChunks, float lodRadiusBlocks) {
        if (!auto) {
            return 0.0f;
        }
        float near = Math.clamp(renderDistanceChunks,
                MIN_RENDER_DISTANCE_CHUNKS, MAX_RENDER_DISTANCE_CHUNKS) * 16f;
        float far = MAX_LOD_DISTANCE_CHUNKS * 16f;
        if (lodRadiusBlocks <= near) {
            return 0.0f;
        }
        float radius = Math.min(lodRadiusBlocks, far);
        displayedFadeMin = radius * FADE_MIN_RATIO;
        displayedFadeMax = radius * FADE_MAX_RATIO;
        return (radius - near) / (far - near);
    }

    public boolean isAuto() {
        return auto;
    }

    public void setAuto(boolean auto) {
        this.auto = auto;
    }

    public String describe() {
        return (auto ? "auto (from render distance)" : "manual (32-chunk defaults)")
            + ", fade " + Math.round(displayedFadeMin) + " to " + Math.round(displayedFadeMax) + " blocks";
    }
}
