/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.nvidia.rtxremix.minecraft.sky.VanillaStarField;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reads the star field vanilla's canonical builder generates, so the Remix sky publishes the
 * same stars Minecraft draws.
 *
 * <p>1.21.1 built the stars in {@code LevelRenderer.drawStars} and returned the mesh; 26.3 builds
 * them in {@code SkyRenderer.buildStars}, closes the mesh as soon as it is uploaded, and keeps
 * only a GPU buffer. The positions are therefore copied from the mesh the moment it is built,
 * before vanilla uploads and closes it. Vanilla only builds them in a sky pass it runs, so
 * until it does {@link VanillaStarField#copyPositions()} hands the Remix sky the identical
 * field generated from vanilla's seed.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    @WrapOperation(
            method = "buildStars",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;buildOrThrow()Lcom/mojang/blaze3d/vertex/MeshData;"))
    private MeshData rtxremix$captureStarVertices(BufferBuilder builder, Operation<MeshData> original) {
        MeshData mesh = original.call(builder);
        VanillaStarField.capture(mesh);
        return mesh;
    }
}
