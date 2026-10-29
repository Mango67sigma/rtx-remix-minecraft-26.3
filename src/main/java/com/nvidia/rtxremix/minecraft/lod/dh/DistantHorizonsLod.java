/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import com.nvidia.rtxremix.minecraft.lod.LodCapture;
import com.nvidia.rtxremix.minecraft.lod.LodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The renderer's way in to the Distant Horizons capture. Its signatures name no Distant
 * Horizons type, so it loads without the mod; the source it creates is only loaded when
 * {@link DistantHorizonsSupport#applies()}.
 */
public final class DistantHorizonsLod {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistantHorizonsLod.class);

    private DistantHorizonsLod() {}

    /**
     * The Distant Horizons LOD source when the capture applies, or null, saying in the log
     * why an installed Distant Horizons is left to itself.
     */
    public static LodSource createSource() {
        String version = DistantHorizonsSupport.installedVersion();
        if (version == null) return null;
        if (!DistantHorizonsSupport.ENABLED) {
            LOGGER.info("Distant Horizons {} found; LOD terrain capture is off (rtxremix.lod.distantHorizons=false)",
                    version);
            return null;
        }
        if (!DistantHorizonsSupport.SUPPORTED_VERSION.equals(version)) {
            LOGGER.warn("Distant Horizons {} found, but this build supports {} only; its LOD terrain is left to it",
                    version, DistantHorizonsSupport.SUPPORTED_VERSION);
            return null;
        }
        LOGGER.info("Distant Horizons {} found; Remix path traces its LOD terrain", version);
        return new DistantHorizonsLodSource(LodCapture.shared(), version);
    }
}
