/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether the Distant Horizons capture applies: the mod is installed in the one version
 * whose internals the capture hooks were written against, and the kill switch is on. The
 * mixin plugin and the renderer both ask here, so the hooks and the capture are on or off
 * together. Names no Distant Horizons type, so it loads without the mod.
 */
public final class DistantHorizonsSupport {

    public static final String MOD_ID = "distanthorizons";
    /** The version the hooks' anchors were verified against; another may have moved them. */
    public static final String SUPPORTED_VERSION = "3.3.4";
    /** Kill switch: {@code -Drtxremix.lod.distantHorizons=false} leaves Distant Horizons to itself. */
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("rtxremix.lod.distantHorizons", "true"));

    private DistantHorizonsSupport() {}

    /** The installed Distant Horizons version, or null when it is not installed. */
    public static String installedVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
    }

    /** Whether the capture hooks are applied and the capture runs. */
    public static boolean applies() {
        return ENABLED && SUPPORTED_VERSION.equals(installedVersion());
    }
}
