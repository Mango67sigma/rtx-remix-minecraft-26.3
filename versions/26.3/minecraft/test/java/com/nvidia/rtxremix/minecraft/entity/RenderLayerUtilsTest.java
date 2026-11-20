/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the layer verdicts that decide what the submitter does with a layer's parts.
 *
 * <p>The surface-mode verdict decides the material a layer is built with. The
 * streamed-per-frame verdict decides which layers bypass the mesh pool: a false positive
 * rebuilds a shareable mesh every frame, a false negative pools a mesh whose content
 * never repeats and fills the pool with dead entries. Their precedence is tested through
 * the pure helpers. Since 26.2 a render type is plain data, a pipeline and the textures
 * it binds, so the glint verdicts are tested on vanilla's own render types.
 */
class RenderLayerUtilsTest {

    private static final Identifier ARMOR_LAYER =
            Identifier.withDefaultNamespace("textures/entity/equipment/humanoid/diamond.png");
    private static final Identifier TRIDENT = Identifier.withDefaultNamespace("textures/entity/trident.png");

    @Test
    void theGlintTexturesAreVanillasOwn() {
        assertEquals(ItemFeatureRenderer.ENCHANTED_GLINT_ITEM, RenderLayerUtils.ITEM_GLINT_TEXTURE);
        assertEquals(ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR, RenderLayerUtils.ARMOR_GLINT_TEXTURE);
    }

    /**
     * The three glints are vanilla's three glint texture transforms, each with the texture
     * vanilla pairs it with ({@code TextureTransform.java}: {@code setupGlintTexturing(8)},
     * {@code (0.5)} and {@code (0.16)}, turned by pi/18).
     */
    @Test
    void theGlintsAreVanillasTransformsAndTextures() {
        assertEquals(RenderLayerUtils.ITEM_GLINT_TEXTURE, RenderLayerUtils.Glint.ITEM.texture);
        assertEquals(8.0f, RenderLayerUtils.Glint.ITEM.scale);
        assertEquals(RenderLayerUtils.ITEM_GLINT_TEXTURE, RenderLayerUtils.Glint.ENTITY.texture);
        assertEquals(0.5f, RenderLayerUtils.Glint.ENTITY.scale);
        assertEquals(RenderLayerUtils.ARMOR_GLINT_TEXTURE, RenderLayerUtils.Glint.ARMOR.texture);
        assertEquals(0.16f, RenderLayerUtils.Glint.ARMOR.scale);
        assertEquals((float) Math.toRadians(10.0), RenderLayerUtils.Glint.ROTATION, 1e-7f);
    }

    /**
     * Every single-pass glint render type vanilla draws with names its glint: the item glint
     * for items on either atlas, the entity glint for tridents and plain shields, the armour
     * glint for armour and elytra.
     */
    @Test
    void everySinglePassGlintTypeNamesItsGlint() {
        for (Identifier atlas : new Identifier[] {TextureAtlas.LOCATION_ITEMS, TextureAtlas.LOCATION_BLOCKS}) {
            assertSame(RenderLayerUtils.Glint.ITEM, RenderLayerUtils.glint(RenderTypes.itemCutoutGlint(atlas)));
            assertSame(RenderLayerUtils.Glint.ITEM, RenderLayerUtils.glint(RenderTypes.itemCutoutGlintSpecial(atlas)));
            assertSame(RenderLayerUtils.Glint.ITEM, RenderLayerUtils.glint(RenderTypes.itemTranslucentGlint(atlas)));
            assertSame(RenderLayerUtils.Glint.ITEM,
                    RenderLayerUtils.glint(RenderTypes.itemTranslucentGlintSpecial(atlas)));
        }
        assertSame(RenderLayerUtils.Glint.ENTITY, RenderLayerUtils.glint(RenderTypes.entitySolidGlint(TRIDENT)));
        assertSame(RenderLayerUtils.Glint.ARMOR,
                RenderLayerUtils.glint(RenderTypes.armorCutoutNoCullGlint(ARMOR_LAYER)));
    }

    /** A render type without a glint sampler has no glint, vanilla's separate glint passes included. */
    @Test
    void aTypeWithoutAGlintSamplerHasNoGlint() {
        assertNull(RenderLayerUtils.glint(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS)));
        assertNull(RenderLayerUtils.glint(RenderTypes.itemTranslucent(TextureAtlas.LOCATION_BLOCKS)));
        assertNull(RenderLayerUtils.glint(RenderTypes.entitySolid(TRIDENT)));
        assertNull(RenderLayerUtils.glint(RenderTypes.armorCutoutNoCull(ARMOR_LAYER)));
        assertNull(RenderLayerUtils.glint(RenderTypes.patternedShieldGlint()));
        assertNull(RenderLayerUtils.glint(RenderTypes.trimmedArmorGlint()));
    }

    /**
     * The transform decides the glint, so a mod pairing vanilla's transform with a texture of
     * its own still gets that glint's scale; with a transform of its own, the texture decides,
     * and a texture vanilla has no glint for takes the item glint's.
     */
    @Test
    void theTransformDecidesTheGlintAndTheTextureOnlyWithoutOne() {
        Identifier modTexture = Identifier.fromNamespaceAndPath("somemod", "textures/misc/glint.png");
        assertSame(RenderLayerUtils.Glint.ITEM,
                RenderLayerUtils.glintFor(TextureTransform.GLINT_TEXTURING, RenderLayerUtils.ARMOR_GLINT_TEXTURE));
        assertSame(RenderLayerUtils.Glint.ENTITY,
                RenderLayerUtils.glintFor(TextureTransform.ENTITY_GLINT_TEXTURING, modTexture));
        assertSame(RenderLayerUtils.Glint.ARMOR,
                RenderLayerUtils.glintFor(TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING, RenderLayerUtils.ITEM_GLINT_TEXTURE));
        assertSame(RenderLayerUtils.Glint.ARMOR,
                RenderLayerUtils.glintFor(TextureTransform.DEFAULT_TEXTURING, RenderLayerUtils.ARMOR_GLINT_TEXTURE));
        assertSame(RenderLayerUtils.Glint.ITEM, RenderLayerUtils.glintFor(TextureTransform.DEFAULT_TEXTURING, modTexture));
    }

    /**
     * Vanilla's separate glint passes, the patterned shield's and the trimmed armour's, are
     * not captured: they have no surface to carry the glint, and as a layer of their own they
     * would hide the surface they cover.
     */
    @Test
    void vanillasSeparateGlintPassesAreNotCaptured() {
        assertTrue(RenderLayerUtils.isGlintPass(RenderPipelines.GLINT));
        assertEquals(Optional.empty(), RenderLayerUtils.capturableTexture(RenderTypes.patternedShieldGlint()));
        assertEquals(Optional.empty(), RenderLayerUtils.capturableTexture(RenderTypes.trimmedArmorGlint()));
        assertFalse(RenderLayerUtils.isCapturableUntexturedLayer(RenderTypes.patternedShieldGlint()));
        assertFalse(RenderLayerUtils.isCapturableUntexturedLayer(RenderTypes.trimmedArmorGlint()));
        for (RenderType glinted : new RenderType[] {
                RenderTypes.itemCutoutGlint(TextureAtlas.LOCATION_ITEMS),
                RenderTypes.itemTranslucentGlint(TextureAtlas.LOCATION_BLOCKS),
                RenderTypes.entitySolidGlint(TRIDENT),
                RenderTypes.armorCutoutNoCullGlint(ARMOR_LAYER)}) {
            assertFalse(RenderLayerUtils.isGlintPass(glinted.pipeline()), glinted.toString());
        }
    }

    /** A glinted surface keeps the texture and the material mode it has without its glint. */
    @Test
    void aGlintedSurfaceKeepsTheTextureAndModeOfItsPlainType() {
        assertSurfaceMatches(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS),
                RenderTypes.itemCutoutGlint(TextureAtlas.LOCATION_ITEMS));
        assertSurfaceMatches(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS),
                RenderTypes.itemCutoutGlintSpecial(TextureAtlas.LOCATION_ITEMS));
        assertSurfaceMatches(RenderTypes.itemTranslucent(TextureAtlas.LOCATION_BLOCKS),
                RenderTypes.itemTranslucentGlint(TextureAtlas.LOCATION_BLOCKS));
        assertSurfaceMatches(RenderTypes.entitySolid(TRIDENT), RenderTypes.entitySolidGlint(TRIDENT));
        assertSurfaceMatches(RenderTypes.armorCutoutNoCull(ARMOR_LAYER), RenderTypes.armorCutoutNoCullGlint(ARMOR_LAYER));
    }

    private static void assertSurfaceMatches(RenderType plain, RenderType glinted) {
        assertEquals(RenderLayerUtils.capturableTexture(plain), RenderLayerUtils.capturableTexture(glinted));
        assertEquals(RenderLayerUtils.surfaceMode(plain), RenderLayerUtils.surfaceMode(glinted));
        assertEquals(RenderLayerUtils.topology(plain), RenderLayerUtils.topology(glinted));
        assertEquals(RenderLayerUtils.isStreamedPerFrame(plain), RenderLayerUtils.isStreamedPerFrame(glinted));
    }

    /** Only the eyes' kind of layer emits its own texture; every other layer emits its LabPBR map or its glint. */
    @Test
    void onlyEmissiveLayersEmitTheirAlbedo() {
        assertTrue(RemixEntitySubmitter.emitsAlbedo(RenderLayerUtils.SurfaceMode.EMISSIVE));
        for (RenderLayerUtils.SurfaceMode mode : RenderLayerUtils.SurfaceMode.values()) {
            if (mode == RenderLayerUtils.SurfaceMode.EMISSIVE) continue;
            assertFalse(RemixEntitySubmitter.emitsAlbedo(mode), mode.name());
        }
        assertFalse(RemixEntitySubmitter.shouldAttachSubsurface(true, RenderLayerUtils.SurfaceMode.EMISSIVE));
    }

    @ParameterizedTest
    @CsvSource({
            "false, false, true, false, true, CUTOUT",
            "false, false, true, false, false, TRANSLUCENT",
            "true, false, true, false, true, EMISSIVE",
            "false, true, false, false, false, EMISSIVE",
            "false, false, false, true, false, OPAQUE"
    })
    void surfaceModeFollowsProgramAndTransparencyPrecedence(
            boolean emissiveProgram, boolean additiveTransparency,
            boolean translucentTransparency, boolean solidProgram,
            boolean textProgram, RenderLayerUtils.SurfaceMode expected) {
        assertEquals(expected, RenderLayerUtils.classifySurfaceMode(
                emissiveProgram, additiveTransparency, translucentTransparency,
                solidProgram, textProgram));
    }

    @ParameterizedTest
    @CsvSource({
            "TRIANGLE_STRIP, false, true",
            "QUADS, true, true",
            "TRIANGLE_STRIP, true, true",
            "QUADS, false, false"
    })
    void stripsAndBeaconBeamsAreStreamedPerFrame(
            RenderLayerUtils.Topology topology, boolean beaconBeamProgram, boolean expected) {
        assertEquals(expected, RenderLayerUtils.classifyStreamedPerFrame(topology, beaconBeamProgram));
    }
}
