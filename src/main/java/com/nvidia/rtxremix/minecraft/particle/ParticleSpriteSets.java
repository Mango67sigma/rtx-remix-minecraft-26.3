/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.minecraft.mixin.ParticleManagerAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ParticleResourcesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleResources;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla's loaded sprite set for a particle type, for code that draws a
 * particle's sprites without spawning the particle (held-item flame and smoke).
 *
 * <p>Since 1.21.9 the sprite sets live on {@link ParticleResources}, the
 * reload listener the {@code ParticleEngine} holds, rather than on the engine.
 * The returned set is vanilla's live one, rebound in place on every resource
 * reload, so a caller may keep it.
 */
public final class ParticleSpriteSets {
    private ParticleSpriteSets() {}

    /** The sprite set registered for {@code type}, or null when it has none. */
    public static @Nullable SpriteSet get(Minecraft client, ParticleType<?> type) {
        if (client.particleEngine == null) {
            return null;
        }
        ParticleResources resources = ((ParticleManagerAccessor) client.particleEngine).getResources();
        if (resources == null) {
            return null;
        }
        Object set = ((ParticleResourcesAccessor) resources).getSpriteSets()
                .get(BuiltInRegistries.PARTICLE_TYPE.getKey(type));
        return set instanceof SpriteSet spriteSet ? spriteSet : null;
    }
}
