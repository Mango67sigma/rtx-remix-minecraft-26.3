/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the Distant Horizons capture mixins only when {@link DistantHorizonsSupport}
 * says the capture runs. Mixin consults the plugin before it looks a target up, so the
 * targets are never searched for without the mod. Lives outside the mixin package, which
 * Mixin reserves for mixins. Follows {@code DistantHorizonsMixinPlugin} in
 * kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
public final class DistantHorizonsMixinPlugin implements IMixinConfigPlugin {

    private boolean applies;

    @Override
    public void onLoad(String mixinPackage) {
        applies = DistantHorizonsSupport.applies();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return applies;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
