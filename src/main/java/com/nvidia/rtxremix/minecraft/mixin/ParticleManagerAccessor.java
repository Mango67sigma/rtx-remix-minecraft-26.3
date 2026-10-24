/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.ParticleResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The engine's live particle groups, keyed by group type, and the
 * {@link ParticleResources} reload listener that has owned the providers and
 * sprite sets since 1.21.9. Its sprite sets are read through
 * {@link ParticleResourcesAccessor}; callers outside the particle package use
 * {@code ParticleSpriteSets}.
 */
@Mixin(ParticleEngine.class)
public interface ParticleManagerAccessor {
    @Accessor("particles")
    Map<ParticleRenderType, ParticleGroup<?>> getParticles();

    @Accessor("resourceManager")
    ParticleResources getResources();
}
