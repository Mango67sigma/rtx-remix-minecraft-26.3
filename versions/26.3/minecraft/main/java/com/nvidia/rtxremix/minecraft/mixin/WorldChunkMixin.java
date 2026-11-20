/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.SkyLightSurfaceScanPolicy;
import com.nvidia.rtxremix.minecraft.terrain.SkyLightSurfaceAccess;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Seeds the sky-light surface scan of a chunk the client receives from the packet's
 * {@code WORLD_SURFACE} heightmap. {@code replaceWithPacketData} is the client chunk apply:
 * it reads the sections, installs the packet's heightmaps and only then calls
 * {@code initializeLightSources}, so the heightmap the seed reads is the one the packet
 * carried. The server's light-source fill during chunk generation is untouched.
 */
@Mixin(LevelChunk.class)
public abstract class WorldChunkMixin {

    @WrapOperation(
            method = "replaceWithPacketData",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;initializeLightSources()V"))
    private void rtxremix$seedSurfaceScan(
            LevelChunk self,
            Operation<Void> original,
            int chunkX,
            int chunkZ,
            ClientboundLevelChunkPacketData chunkData) {
        boolean worldSurfaceInPacket = chunkData.getHeightmaps().containsKey(Heightmap.Types.WORLD_SURFACE);
        if (!SkyLightSurfaceScanPolicy.seedFromHeightmap(SkyLightSurfaceScanPolicy.SEED_ALLOWED, worldSurfaceInPacket)) {
            if (SkyLightSurfaceScanPolicy.SEED_ALLOWED) {
                SkyLightSurfaceScanPolicy.noteMissingHeightmap();
            }
            original.call(self);
            return;
        }
        ((SkyLightSurfaceAccess) self.getSkyLightSources())
                .rtxremix$refreshSurfaceY(self, self.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE));
        // Counted only while capture is on, like the paced-chunk counters: the terrain
        // counters that drain it run only while capture and world rendering are on, and
        // seeding itself depends on neither toggle.
        if (RtxRemixMod.isRtxEnabled()) {
            SkyLightSurfaceScanPolicy.recordSeeded();
        }
    }
}
