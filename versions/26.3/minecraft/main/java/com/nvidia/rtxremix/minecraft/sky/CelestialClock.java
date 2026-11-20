/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;

/**
 * The celestial clock the sky reads, from the same source vanilla's own sky reads.
 *
 * <p>Since 26.1 a level has no day time of its own: world clocks drive timelines, and the
 * timelines publish the sun, moon and star angles, the moon phase and the star brightness as
 * environment attributes. Vanilla's {@code SkyRenderer.extractRenderState} samples them through
 * the main camera's {@link EnvironmentAttributeProbe} with the frame's partial tick, so the sky
 * the mod publishes and the sky vanilla would have drawn agree on every frame, including in a
 * dimension whose timeline a datapack replaced.
 *
 * <p>Render thread only, like the probe it reads.
 */
public final class CelestialClock {

    private CelestialClock() {
    }

    /**
     * Returns the sun angle as the fraction of a turn 1.21.1's {@code getTimeOfDay} returned:
     * 0 at noon, 0.25 at sunset, 0.5 at midnight. This is what vanilla's clock item reads as
     * its {@code daytime} property ({@code SUN_ANGLE / 360}).
     */
    public static float skyAngle(float partialTicks) {
        return probe().getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks) / 360.0f;
    }

    /** Returns the star field's rotation as a fraction of a turn, 0 when it is upright at noon. */
    public static float starAngle(float partialTicks) {
        return probe().getValue(EnvironmentAttributes.STAR_ANGLE, partialTicks) / 360.0f;
    }

    /** Returns the moon phase index, 0 (full) to 7, in the order of the vanilla moon textures. */
    public static int moonPhase(float partialTicks) {
        MoonPhase phase = probe().getValue(EnvironmentAttributes.MOON_PHASE, partialTicks);
        return phase.index();
    }

    /**
     * Returns the star brightness 1.21.1's {@code ClientLevel.getStarBrightness} derived from the
     * sky angle, before any weather. 26.3's {@code STAR_BRIGHTNESS} attribute already folds rain
     * and thunder in, but the mod applies its own weather visibility with a configurable floor,
     * so it needs the clear-sky value; this is that value, and it peaks at 0.5 at midnight.
     */
    public static float clearSkyStarBrightness(float skyAngle) {
        float brightness = 1.0f - ((float) Math.cos(skyAngle * (float) (Math.PI * 2.0)) * 2.0f + 0.25f);
        brightness = Math.max(0.0f, Math.min(1.0f, brightness));
        return brightness * brightness * 0.5f;
    }

    private static EnvironmentAttributeProbe probe() {
        return Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe();
    }
}
