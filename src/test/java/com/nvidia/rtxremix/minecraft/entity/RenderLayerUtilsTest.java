/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.Optional;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void aGlintSamplersTextureNamesThePassItIsCapturedUnder() {
        assertEquals(RenderLayerUtils.ITEM_GLINT_TEXTURE,
                RenderLayerUtils.glintPassTexture(RenderLayerUtils.ITEM_GLINT_TEXTURE));
        assertEquals(RenderLayerUtils.ARMOR_GLINT_TEXTURE,
                RenderLayerUtils.glintPassTexture(RenderLayerUtils.ARMOR_GLINT_TEXTURE));
        // Vanilla has no pass for a mod's own glint texture; it takes the item glint's.
        assertEquals(RenderLayerUtils.ITEM_GLINT_TEXTURE,
                RenderLayerUtils.glintPassTexture(Identifier.fromNamespaceAndPath("somemod", "textures/misc/glint.png")));
    }

    /**
     * Every single-pass glint render type vanilla draws with names the separate pass the
     * capture records its glint under: the item glint for items, tridents and shields, the
     * armour glint for armour, whose texture differs.
     */
    @Test
    void everySinglePassGlintTypeNamesVanillasSeparateGlintPass() {
        RenderType itemPass = RenderTypes.patternedShieldGlint();
        RenderType armorPass = RenderTypes.trimmedArmorGlint();
        for (Identifier atlas : new Identifier[] {TextureAtlas.LOCATION_ITEMS, TextureAtlas.LOCATION_BLOCKS}) {
            assertSame(itemPass, RenderLayerUtils.glintPass(RenderTypes.itemCutoutGlint(atlas)));
            assertSame(itemPass, RenderLayerUtils.glintPass(RenderTypes.itemCutoutGlintSpecial(atlas)));
            assertSame(itemPass, RenderLayerUtils.glintPass(RenderTypes.itemTranslucentGlint(atlas)));
            assertSame(itemPass, RenderLayerUtils.glintPass(RenderTypes.itemTranslucentGlintSpecial(atlas)));
        }
        assertSame(itemPass, RenderLayerUtils.glintPass(RenderTypes.entitySolidGlint(TRIDENT)));
        assertSame(armorPass, RenderLayerUtils.glintPass(RenderTypes.armorCutoutNoCullGlint(ARMOR_LAYER)));
    }

    /** A render type without a glint sampler, a glint pass itself included, adds no glint layer. */
    @Test
    void aTypeWithoutAGlintSamplerHasNoGlintPass() {
        assertNull(RenderLayerUtils.glintPass(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS)));
        assertNull(RenderLayerUtils.glintPass(RenderTypes.itemTranslucent(TextureAtlas.LOCATION_BLOCKS)));
        assertNull(RenderLayerUtils.glintPass(RenderTypes.entitySolid(TRIDENT)));
        assertNull(RenderLayerUtils.glintPass(RenderTypes.armorCutoutNoCull(ARMOR_LAYER)));
        assertNull(RenderLayerUtils.glintPass(RenderTypes.patternedShieldGlint()));
        assertNull(RenderLayerUtils.glintPass(RenderTypes.trimmedArmorGlint()));
    }

    /**
     * The glint layer is what 1.21.1 captured: the glint texture alone, read as a cutout,
     * quads, pooled. Its pipeline blends with {@code BlendFunction.GLINT}, which is neither
     * the additive nor the translucent function, just as 1.21.1's glint transparency shard
     * was neither of those shards.
     */
    @Test
    void theGlintPassIsACutoutLayerOfTheGlintTextureAlone() {
        assertEquals(Optional.of(RenderLayerUtils.ITEM_GLINT_TEXTURE),
                RenderLayerUtils.capturableTexture(RenderTypes.patternedShieldGlint()));
        assertEquals(Optional.of(RenderLayerUtils.ARMOR_GLINT_TEXTURE),
                RenderLayerUtils.capturableTexture(RenderTypes.trimmedArmorGlint()));
        for (RenderType pass : new RenderType[] {RenderTypes.patternedShieldGlint(), RenderTypes.trimmedArmorGlint()}) {
            assertEquals(RenderLayerUtils.SurfaceMode.CUTOUT, RenderLayerUtils.surfaceMode(pass));
            assertEquals(Optional.of(RenderLayerUtils.Topology.QUADS), RenderLayerUtils.topology(pass));
            assertFalse(RenderLayerUtils.isStreamedPerFrame(pass));
        }
    }

    /** The surface under a glint keeps the texture and the material it has without one. */
    @Test
    void aGlintedSurfaceKeepsTheTextureAndModeOfItsPlainType() {
        assertSurfaceMatches(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS),
                RenderTypes.itemCutoutGlint(TextureAtlas.LOCATION_ITEMS));
        assertSurfaceMatches(RenderTypes.itemTranslucent(TextureAtlas.LOCATION_BLOCKS),
                RenderTypes.itemTranslucentGlint(TextureAtlas.LOCATION_BLOCKS));
        assertSurfaceMatches(RenderTypes.entitySolid(TRIDENT), RenderTypes.entitySolidGlint(TRIDENT));
        assertSurfaceMatches(RenderTypes.armorCutoutNoCull(ARMOR_LAYER), RenderTypes.armorCutoutNoCullGlint(ARMOR_LAYER));
    }

    private static void assertSurfaceMatches(RenderType plain, RenderType glinted) {
        assertEquals(RenderLayerUtils.capturableTexture(plain), RenderLayerUtils.capturableTexture(glinted));
        assertEquals(RenderLayerUtils.surfaceMode(plain), RenderLayerUtils.surfaceMode(glinted));
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
