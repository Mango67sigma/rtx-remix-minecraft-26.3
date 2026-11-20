/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.Identifier;

/**
 * The material cache stores records and is probed with a mutable key. HashMap calls
 * probe.equals(stored) and requires equal hash codes, so a drift between the two
 * misses every lookup and silently drops every entity layer.
 */
class EntityMaterialKeyTest {

    private static final Identifier TEX = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/cow/cow.png");

    @Test
    void lookupProbeAgreesWithTheStoredRecordOnHashAndEquality() {
        for (RenderLayerUtils.Glint glint : new RenderLayerUtils.Glint[] {null, RenderLayerUtils.Glint.ARMOR}) {
            RemixEntitySubmitter.EntityMaterialKey stored =
                    new RemixEntitySubmitter.EntityMaterialKey(TEX, RenderLayerUtils.SurfaceMode.CUTOUT, glint);
            RemixEntitySubmitter.EntityMaterialLookupKey probe = new RemixEntitySubmitter.EntityMaterialLookupKey()
                    .set(TEX, RenderLayerUtils.SurfaceMode.CUTOUT, glint);

            assertEquals(stored.hashCode(), probe.hashCode());
            assertTrue(probe.equals(stored));
        }
    }

    @Test
    void surfaceModeSeparatesMaterialsOnOneTexture() {
        RemixEntitySubmitter.EntityMaterialLookupKey probe = new RemixEntitySubmitter.EntityMaterialLookupKey()
                .set(TEX, RenderLayerUtils.SurfaceMode.CUTOUT, null);

        assertFalse(probe.equals(
                new RemixEntitySubmitter.EntityMaterialKey(TEX, RenderLayerUtils.SurfaceMode.TRANSLUCENT, null)));
    }

    /** An enchanted surface is its plain counterpart's texture and mode under a material of its own. */
    @Test
    void glintSeparatesMaterialsOnOneTextureAndMode() {
        RemixEntitySubmitter.EntityMaterialLookupKey plain = new RemixEntitySubmitter.EntityMaterialLookupKey()
                .set(TEX, RenderLayerUtils.SurfaceMode.CUTOUT, null);
        RemixEntitySubmitter.EntityMaterialLookupKey armor = new RemixEntitySubmitter.EntityMaterialLookupKey()
                .set(TEX, RenderLayerUtils.SurfaceMode.CUTOUT, RenderLayerUtils.Glint.ARMOR);

        assertFalse(plain.equals(new RemixEntitySubmitter.EntityMaterialKey(
                TEX, RenderLayerUtils.SurfaceMode.CUTOUT, RenderLayerUtils.Glint.ARMOR)));
        assertFalse(armor.equals(new RemixEntitySubmitter.EntityMaterialKey(
                TEX, RenderLayerUtils.SurfaceMode.CUTOUT, null)));
        assertFalse(armor.equals(new RemixEntitySubmitter.EntityMaterialKey(
                TEX, RenderLayerUtils.SurfaceMode.CUTOUT, RenderLayerUtils.Glint.ITEM)));
    }
}
