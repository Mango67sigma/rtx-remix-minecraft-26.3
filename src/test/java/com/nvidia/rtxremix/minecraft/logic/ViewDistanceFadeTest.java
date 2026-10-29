/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewDistanceFadeTest {

    // The options the endpoint confs carry; the fade itself only computes the
    // strength that blends between them.
    private static final String FADE_MIN_KEY = "rtx.viewDistance.distanceFadeMin";
    private static final String FADE_MAX_KEY = "rtx.viewDistance.distanceFadeMax";

    @Test
    void theMappingAtThirtyTwoChunksMatchesTheCalibrationRatios() {
        // 32 chunks = 512 blocks; the band occupies 70%..80% of that range.
        assertEquals(358.4f, ViewDistanceFade.computeFadeMin(32), 0.01f);
        assertEquals(409.6f, ViewDistanceFade.computeFadeMax(32), 0.01f);
    }

    @Test
    void layerStrengthMapsTheVanillaRangeOntoTheEndpointProfile() {
        ViewDistanceFade fade = new ViewDistanceFade();
        assertEquals(0.0f, fade.layerStrength(32), 1e-6f);
        assertEquals(0.5f, fade.layerStrength(17), 1e-6f);
        assertEquals(1.0f, fade.layerStrength(2), 1e-6f);
        assertEquals(0.0f, fade.layerStrength(64), 1e-6f, "values above vanilla's range clamp");
        assertEquals(1.0f, fade.layerStrength(1), 1e-6f, "values below vanilla's range clamp");
    }

    @Test
    void layerStrengthWithAutoOffSelectsTheBaselineAtEveryDistance() {
        ViewDistanceFade fade = new ViewDistanceFade();
        fade.layerStrength(ViewDistanceFade.MIN_RENDER_DISTANCE_CHUNKS);
        fade.setAuto(false);

        assertEquals(0.0f, fade.layerStrength(2), 1e-6f);
        assertEquals(0.0f, fade.layerStrength(32), 1e-6f);
        // /rtx viewdistance and the settings screen must name the baseline the
        // zero strength selects, not the band auto mode last reported.
        assertEquals("manual (32-chunk defaults), fade "
                + Math.round(ViewDistanceFade.RESTORE_FADE_MIN) + " to "
                + Math.round(ViewDistanceFade.RESTORE_FADE_MAX) + " blocks",
                fade.describe());
    }

    @Test
    void layerStrengthReconstructsTheLinearFadeBand() {
        ViewDistanceFade fade = new ViewDistanceFade();
        for (int chunks = ViewDistanceFade.MIN_RENDER_DISTANCE_CHUNKS;
                chunks <= ViewDistanceFade.MAX_RENDER_DISTANCE_CHUNKS; chunks++) {
            float strength = fade.layerStrength(chunks);
            assertEquals(ViewDistanceFade.computeFadeMin(chunks),
                    lerp(ViewDistanceFade.RESTORE_FADE_MIN, ViewDistanceFade.LAYER_FADE_MIN, strength),
                    0.001f, "fade min at " + chunks + " chunks");
            assertEquals(ViewDistanceFade.computeFadeMax(chunks),
                    lerp(ViewDistanceFade.RESTORE_FADE_MAX, ViewDistanceFade.LAYER_FADE_MAX, strength),
                    0.001f, "fade max at " + chunks + " chunks");
        }
    }

    @Test
    void lodLayerStrengthIsZeroWithoutLodTerrainBeyondTheRenderDistance() {
        ViewDistanceFade fade = new ViewDistanceFade();
        fade.layerStrength(12);
        assertEquals(0.0f, fade.lodLayerStrength(12, 0f), 1e-6f);
        assertEquals(0.0f, fade.lodLayerStrength(12, 12 * 16f), 1e-6f);

        fade.setAuto(false);
        fade.layerStrength(12);
        assertEquals(0.0f, fade.lodLayerStrength(12, 4096f), 1e-6f, "manual mode keeps the baseline");
    }

    @Test
    void lodLayerPlacesTheFadeBandAtTheLodRadius() {
        ViewDistanceFade fade = new ViewDistanceFade();
        for (int chunks : new int[] {2, 12, 32}) {
            for (float radius : new float[] {1024f, 4096f, 16384f, 65536f}) {
                float near = fade.layerStrength(chunks);
                float lod = fade.lodLayerStrength(chunks, radius);
                float fadeMin = lerp(lerp(ViewDistanceFade.RESTORE_FADE_MIN, ViewDistanceFade.LAYER_FADE_MIN, near),
                        ViewDistanceFade.LOD_LAYER_FADE_MIN, lod);
                float fadeMax = lerp(lerp(ViewDistanceFade.RESTORE_FADE_MAX, ViewDistanceFade.LAYER_FADE_MAX, near),
                        ViewDistanceFade.LOD_LAYER_FADE_MAX, lod);
                assertEquals(radius * 0.7f, fadeMin, radius * 1e-4f, "fade min at " + chunks + " chunks, " + radius);
                assertEquals(radius * 0.8f, fadeMax, radius * 1e-4f, "fade max at " + chunks + " chunks, " + radius);
            }
        }
        fade.layerStrength(12);
        fade.lodLayerStrength(12, 4096f);
        assertEquals("auto (from render distance), fade 2867 to 3277 blocks", fade.describe());
    }

    @Test
    void endpointProfilesMatchControllerConstants() throws Exception {
        Map<String, Float> baseline = readConf(Path.of("packaging", "rtx.conf"));
        Map<String, Float> minimum = readConf(Path.of("packaging", "rtx-remix", "mods",
                "minecraft-logic", "confs", "view_distance.conf"));
        Map<String, Float> lod = readConf(Path.of("packaging", "rtx-remix", "mods",
                "minecraft-logic", "confs", "view_distance_lod.conf"));

        assertEquals(ViewDistanceFade.RESTORE_FADE_MIN, baseline.get(FADE_MIN_KEY));
        assertEquals(ViewDistanceFade.RESTORE_FADE_MAX, baseline.get(FADE_MAX_KEY));
        assertEquals(ViewDistanceFade.LAYER_FADE_MIN, minimum.get(FADE_MIN_KEY));
        assertEquals(ViewDistanceFade.LAYER_FADE_MAX, minimum.get(FADE_MAX_KEY));
        assertEquals(ViewDistanceFade.LOD_LAYER_FADE_MIN, lod.get(FADE_MIN_KEY));
        assertEquals(ViewDistanceFade.LOD_LAYER_FADE_MAX, lod.get(FADE_MAX_KEY));
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static Map<String, Float> readConf(Path path) throws Exception {
        Map<String, Float> values = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            String[] parts = line.split("=", 2);
            if (parts.length == 2) {
                String key = parts[0].trim();
                if (key.equals(FADE_MIN_KEY) || key.equals(FADE_MAX_KEY)) {
                    values.put(key, Float.parseFloat(parts[1].trim()));
                }
            }
        }
        return values;
    }
}
