/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.particle.ParticleTickScopes;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/**
 * Coarse per-group tick marker. {@code ParticleGroup.tickParticles()} is the
 * 1.21.9+ replacement for {@code ParticleEngine.tickParticleList}, which
 * {@code ParticleManagerMixin} used to bracket; the scope keeps the
 * {@code ParticleManager.tickParticles} name so existing captures still match.
 */
@Mixin(ParticleGroup.class)
public abstract class ParticleGroupMixin {
    @Shadow @Final protected Queue<? extends Particle> particles;

    @Inject(method = "tickParticles", at = @At("HEAD"), require = 0)
    private void rtxremix$tickParticlesHead(CallbackInfo ci) {
        ParticleTickScopes.openGroup(ProfileScope.beginJfrOnly("ParticleManager.tickParticles")
                .captureCount(particles.size())
                .plotOnClose("Java.ParticleManager.tickParticles.ms"));
    }

    @Inject(method = "tickParticles", at = @At("RETURN"), require = 0)
    private void rtxremix$tickParticlesReturn(CallbackInfo ci) {
        ParticleTickScopes.closeGroup();
    }
}
