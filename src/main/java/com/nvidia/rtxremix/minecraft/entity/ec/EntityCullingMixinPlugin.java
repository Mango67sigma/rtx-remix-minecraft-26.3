/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity.ec;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the Entity Culling mixins only when {@link EntityCullingSupport#hooksApply()} says
 * they apply, so without the mod, or with its switch off, vanilla's methods carry no hook of
 * its at all. Mixin consults the plugin before it transforms a target, and the question it
 * asks loads neither an Entity Culling class nor a Minecraft one; whether Entity Culling's
 * flags can be read is decided later, at their first use, and without them the hooks keep
 * nothing. Lives outside the mixin package, which Mixin reserves for mixins. Modelled on
 * {@code DistantHorizonsMixinPlugin}.
 */
public final class EntityCullingMixinPlugin implements IMixinConfigPlugin {

    private boolean applies;

    @Override
    public void onLoad(String mixinPackage) {
        applies = EntityCullingSupport.hooksApply();
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
