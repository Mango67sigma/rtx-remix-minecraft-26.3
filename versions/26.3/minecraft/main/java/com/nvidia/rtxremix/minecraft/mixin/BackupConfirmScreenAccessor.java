/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.gui.screens.BackupConfirmScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The action behind a backup prompt's proceed buttons, which a benchmark world load answers. */
@Mixin(BackupConfirmScreen.class)
public interface BackupConfirmScreenAccessor {
    @Accessor("onProceed")
    BackupConfirmScreen.Listener rtxremix$onProceed();
}
