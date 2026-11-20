/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.particle.ParticleTickScopes;
import com.nvidia.rtxremix.minecraft.profiling.ParticleTickProfiler;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Coarse particle ticking markers. These intentionally avoid per-particle
 * zones because the benchmark scene can tick thousands of particles per frame.
 *
 * <p>Since 1.21.9 the engine ticks each {@link ParticleGroup} through
 * {@code ParticleGroup.tickParticles()}, which replaced
 * {@code ParticleEngine.tickParticleList}; the per-group scope is opened and
 * closed by {@link ParticleGroupMixin}, and this mixin closes a group scope a
 * throwing group left open.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleManagerMixin {
    @Shadow @Final private Map<ParticleRenderType, ParticleGroup<?>> particles;

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void rtxremix$tickHead(CallbackInfo ci) {
        ParticleTickProfiler.beginTick();
        int activeParticles = 0;
        int activeGroups = 0;
        for (ParticleGroup<?> group : particles.values()) {
            int size = group.size();
            activeParticles += size;
            if (size > 0) activeGroups++;
        }
        ParticleTickScopes.openTick(ProfileScope.beginJfrOnly("ParticleManager.tick")
                .captureCount(activeParticles)
                .layerCount(activeGroups)
                .plotOnClose("Java.ParticleManager.tick.ms"));
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void rtxremix$tickReturn(CallbackInfo ci) {
        ParticleTickProfiler.Stats stats = ParticleTickProfiler.current();
        ProfileScope tickScope = ParticleTickScopes.tickScope();
        if (tickScope != null) {
            tickScope.collisionChecks(stats.collisionChecks())
                    .collisionSkips(stats.skippedCollisionChecks())
                    .collisionNanos(stats.collisionNanos());
            ProfileScope.plot("Java.ParticleManager.collisionChecks", stats.collisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionSkips", stats.skippedCollisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionMs", stats.collisionNanos() / 1_000_000.0);
        }
        ParticleTickScopes.closeGroup();
        ParticleTickScopes.closeTick();
    }
}
