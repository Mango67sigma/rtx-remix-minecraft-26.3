/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * A section snapshot for a render region ({@code SectionCopy}, the old per-chunk
 * {@code RenderChunk}) whose block-state copy draws its storage from
 * {@link RenderRegionStoragePool} and gives it back when the last region holding the snapshot
 * has been consumed. Snapshots are shared between the regions one extraction pass builds
 * from the same {@code RenderRegionCache}, hence the count: the cache takes the creator's
 * reference here, at construction, and drops it through
 * {@link ChunkRendererRegionBuilderMixin}; each region adds its own
 * ({@link ChunkRendererRegionMixin}). The pool flag is set around vanilla's own
 * {@code PalettedContainer.copy()} call; the copy code itself is untouched.
 */
@Mixin(SectionCopy.class)
public abstract class RenderedChunkMixin implements RegionSnapshotRelease {

    @Shadow
    @Final
    @Mutable
    private PalettedContainer<BlockState> section;

    private final AtomicInteger rtxremix$regions = new AtomicInteger();

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/chunk/PalettedContainer;copy()Lnet/minecraft/world/level/chunk/PalettedContainer;"))
    private PalettedContainer<BlockState> rtxremix$copyWithPooledStorage(
            PalettedContainer<BlockState> states, Operation<PalettedContainer<BlockState>> original) {
        RenderRegionStoragePool.beginCopying();
        try {
            return original.call(states);
        } finally {
            RenderRegionStoragePool.endCopying();
        }
    }

    /** The creating cache's reference; {@code RenderRegionCache} is the only constructor caller. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$takeCreatorReference(LevelChunk levelChunk, int sectionIndex, CallbackInfo ci) {
        rtxremix$regions.set(1);
    }

    @Override
    public void rtxremix$retain() {
        rtxremix$regions.incrementAndGet();
    }

    @Override
    public void rtxremix$release() {
        if (rtxremix$regions.decrementAndGet() != 0) {
            return;
        }
        PalettedContainer<BlockState> container = section;
        if (container == null) {
            return;
        }
        if (container.data.storage() instanceof SimpleBitStorage packed) {
            RenderRegionStoragePool.release(packed.getRaw());
        }
        // Nothing may read the snapshot after its last release; an air-only read beats
        // returning whatever the recycled array holds next.
        section = null;
    }
}
