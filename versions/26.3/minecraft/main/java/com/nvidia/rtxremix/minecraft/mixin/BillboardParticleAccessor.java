/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The quad state {@code SingleQuadParticle.extract} hands vanilla's render
 * state: tint, roll, sprite, UVs and layer. Since 1.21.9 this class also holds
 * what {@code Particle} (colour, roll) and {@code TextureSheetParticle} (sprite)
 * used to. The size is read through the public {@code getQuadSize(float)}, as
 * vanilla does, so a particle that animates its size is drawn at vanilla's size.
 */
@Mixin(SingleQuadParticle.class)
public interface BillboardParticleAccessor {
    @Accessor("rCol") float getRed();
    @Accessor("gCol") float getGreen();
    @Accessor("bCol") float getBlue();
    @Accessor("alpha") float getAlpha();
    @Accessor("roll") float getAngle();
    @Accessor("oRoll") float getPrevAngle();
    @Accessor("sprite") TextureAtlasSprite getSprite();

    @Invoker("getU0")
    float invokeGetMinU();

    @Invoker("getU1")
    float invokeGetMaxU();

    @Invoker("getV0")
    float invokeGetMinV();

    @Invoker("getV1")
    float invokeGetMaxV();

    @Invoker("getLayer")
    SingleQuadParticle.Layer invokeGetLayer();
}
