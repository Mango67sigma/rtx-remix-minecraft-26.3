/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import com.nvidia.rtxremix.minecraft.lod.LodCapture;
import com.nvidia.rtxremix.minecraft.lod.LodSource;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distant Horizons as the LOD source, through its public API.
 *
 * <p>While capture runs, Distant Horizons draws nothing: both of its render events are
 * cancelled, which still lets it run its render-thread tasks first, so its sections keep
 * completing their uploads and its detail swaps keep going. Two of its options are
 * overridden for as long, in its own API-override layer that its menu shows and that
 * leaves the player's choice in place: it stops baking face shading into its colours,
 * since Remix lights the terrain, and it keeps transparency complete, since only then does
 * it build the ground under water, which Remix sees through. Distant Horizons reloads
 * every section itself about two seconds after either option changes, so each start and
 * each stop rebuilds every section once: that is how sections built before capture began
 * reach it, and how those built during it get real buffers again. Where neither option
 * changes, because it refused both overrides, it is asked to rebuild instead. Distant
 * Horizons' API calls are only valid once it has initialized and loaded a world, so what
 * start cannot do yet is retried every frame. Render thread only.
 *
 * <p>The API calls follow {@code DistantHorizonsCompat} in
 * kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
final class DistantHorizonsLodSource implements LodSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistantHorizonsLodSource.class);
    /** Shown in Distant Horizons' menu beside the option the renderer overrides. */
    private static final String OWNER = "RTX Remix";

    private final String version;
    private boolean capturing;
    private boolean configOverridden;
    private boolean shadingOverrideAccepted;
    private boolean transparencyOverrideAccepted;
    private boolean resendPending;
    private IDhApiConfigValue<Integer> renderDistance;

    DistantHorizonsLodSource(LodCapture capture, String version) {
        this.version = version;
        // Distant Horizons keeps its listeners for the life of the game; they act only while capturing.
        DhApi.events.bind(DhApiBeforeRenderEvent.class, new CancelRender(capture));
        DhApi.events.bind(DhApiBeforeDeferredRenderEvent.class, new CancelDeferredRender(capture));
    }

    @Override
    public void startCapture() {
        capturing = true;
        configOverridden = false;
        resendPending = true;
        update();
    }

    @Override
    public void update() {
        if (!capturing) return;
        if (!configOverridden) {
            // The rebuild waits for the overrides, or it would rebuild with the player's options.
            if (!overrideConfig()) return;
            // An accepted override makes Distant Horizons rebuild every section itself.
            if (shadingOverrideAccepted || transparencyOverrideAccepted) resendPending = false;
        }
        if (resendPending && clearRenderData()) resendPending = false;
    }

    @Override
    public void stopCapture() {
        capturing = false;
        resendPending = false;
        boolean restored = false;
        IDhApiConfig configs = DhApi.Delayed.configs;
        if (configs != null) {
            if (shadingOverrideAccepted && configs.graphics().lodShading().clearValue()) restored = true;
            if (transparencyOverrideAccepted && configs.graphics().transparency().clearValue()) restored = true;
        }
        configOverridden = false;
        shadingOverrideAccepted = false;
        transparencyOverrideAccepted = false;
        // A restored option makes Distant Horizons rebuild every section itself, with real buffers.
        if (!restored) clearRenderData();
    }

    @Override
    public int radiusChunks() {
        if (renderDistance == null) {
            IDhApiConfig configs = DhApi.Delayed.configs;
            if (configs == null) return 0;
            // Kept: the config getter builds a new wrapper on every call.
            renderDistance = configs.graphics().chunkRenderDistance();
        }
        Integer chunks = renderDistance.getValue();
        return chunks != null ? chunks : 0;
    }

    @Override
    public String describe() {
        return "Distant Horizons " + version;
    }

    /** Returns false while Distant Horizons has no configuration to override yet. */
    private boolean overrideConfig() {
        IDhApiConfig configs = DhApi.Delayed.configs;
        if (configs == null) return false;
        shadingOverrideAccepted = configs.graphics().lodShading().setValue(EDhApiLodShading.DISABLED, OWNER);
        if (!shadingOverrideAccepted) {
            LOGGER.warn("Distant Horizons refused the LOD shading override; its LOD colours keep their baked face shading");
        }
        transparencyOverrideAccepted = configs.graphics().transparency().setValue(EDhApiTransparency.COMPLETE, OWNER);
        if (!transparencyOverrideAccepted) {
            LOGGER.warn("Distant Horizons refused the LOD transparency override; with its transparency off,"
                    + " LOD water has no ground under it");
        }
        configOverridden = true;
        return true;
    }

    /** Asks Distant Horizons to rebuild every section; false while it has no world loaded. */
    private static boolean clearRenderData() {
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        return proxy != null && proxy.clearRenderDataCache().success;
    }

    private static final class CancelRender extends DhApiBeforeRenderEvent {
        private final LodCapture capture;

        CancelRender(LodCapture capture) {
            this.capture = capture;
        }

        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            if (capture.isCapturing()) event.cancelEvent();
        }
    }

    private static final class CancelDeferredRender extends DhApiBeforeDeferredRenderEvent {
        private final LodCapture capture;

        CancelDeferredRender(LodCapture capture) {
            this.capture = capture;
        }

        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            if (capture.isCapturing()) event.cancelEvent();
        }
    }
}
