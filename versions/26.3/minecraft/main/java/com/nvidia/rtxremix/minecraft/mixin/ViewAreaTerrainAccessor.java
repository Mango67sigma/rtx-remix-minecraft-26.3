/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The view area's section slots, for the terrain recapture: the set vanilla has built and
 * the slot that holds a given section. {@code getRenderSection} returns null for a section
 * outside the storage's current window, so it never aliases onto another section's slot.
 */
@Mixin(ViewArea.class)
public interface ViewAreaTerrainAccessor {
    @Accessor("sections")
    RotatingSectionStorage<SectionRenderDispatcher.RenderSection> rtxremix$sections();

    @Invoker("getRenderSection")
    SectionRenderDispatcher.RenderSection rtxremix$getRenderSection(long sectionNode);
}
