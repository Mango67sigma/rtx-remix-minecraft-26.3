/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;

/**
 * The open particle-tick profiling scopes, shared by the engine-level and
 * group-level tick mixins ({@code ParticleManagerMixin}, {@code ParticleGroupMixin}).
 * Since 1.21.9 the engine's whole tick and each group's tick are separate
 * methods on separate classes, so the scopes cannot live in one mixin.
 * Ticking runs on the client thread only; the holders are thread-local so a
 * stray caller cannot close another thread's scope.
 */
public final class ParticleTickScopes {
    private static final ThreadLocal<ProfileScope> TICK_SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> GROUP_SCOPE = new ThreadLocal<>();

    private ParticleTickScopes() {}

    public static void openTick(ProfileScope scope) {
        open(TICK_SCOPE, scope);
    }

    public static ProfileScope tickScope() {
        return TICK_SCOPE.get();
    }

    public static void closeTick() {
        close(TICK_SCOPE);
    }

    public static void openGroup(ProfileScope scope) {
        open(GROUP_SCOPE, scope);
    }

    public static void closeGroup() {
        close(GROUP_SCOPE);
    }

    private static void open(ThreadLocal<ProfileScope> holder, ProfileScope scope) {
        close(holder);
        holder.set(scope);
    }

    private static void close(ThreadLocal<ProfileScope> holder) {
        ProfileScope scope = holder.get();
        if (scope != null) {
            holder.remove();
            scope.close();
        }
    }
}
