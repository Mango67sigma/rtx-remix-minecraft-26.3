/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands vanilla's weather geometry to the Remix weather pass and keeps vanilla
 * from drawing it.
 *
 * <p>In 26.3 the weather columns are extracted into {@code WeatherRenderState},
 * built into one GPU vertex buffer by {@code prepare} inside the main frame-graph
 * pass, and drawn by {@code render} (classic transparency) or {@code renderOit}
 * (improved transparency), both through the private three-argument {@code
 * render}. The 1.21.1 port captured the built mesh and texture at vanilla's draw
 * call; the equivalent now is the prepared buffer and the two textures at the
 * end of {@code prepare}, with the column counts the render state carries. The
 * private draw is cancelled at its head while Remix renders the world, so neither
 * transparency mode draws vanilla weather; {@link RemixWeatherRenderer} draws
 * the same buffer later, over the composited Remix image.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

    @Shadow
    private @Nullable GpuBuffer vertexBuffer;

    @Shadow
    private @Nullable AbstractTexture rainTexture;

    @Shadow
    private @Nullable AbstractTexture snowTexture;

    @Inject(method = "prepare", at = @At("RETURN"))
    private void rtxremix$captureWeather(Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo ci) {
        RemixWeatherRenderer weather = rtxremix$remixWeather();
        if (weather == null) {
            return;
        }
        weather.capture(
                vertexBuffer,
                rainTexture,
                snowTexture,
                renderState.rainColumns.size(),
                renderState.snowColumns.size());
    }

    @Inject(
            method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void rtxremix$skipVanillaWeatherDraw(CallbackInfo ci) {
        if (rtxremix$remixWeather() != null) {
            ci.cancel();
        }
    }

    private static RemixWeatherRenderer rtxremix$remixWeather() {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            return null;
        }
        return RtxRemixMod.getInstance().getRenderer().getWeatherRenderer();
    }
}
