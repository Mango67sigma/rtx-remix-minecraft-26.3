/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets {@code BuiltChunkStorageMixin} finish a re-centre it performed itself the way
 * {@code RotatingSectionStorage.repositionCenter} finishes one: by recording the new centre,
 * which {@code getValue} uses to decide which sections the window holds.
 */
@Mixin(RotatingSectionStorage.class)
public interface RotatingSectionStorageAccessor {
    @Accessor("centerSectionPos")
    void rtxremix$setCenterSectionPos(SectionPos centerSectionPos);
}
