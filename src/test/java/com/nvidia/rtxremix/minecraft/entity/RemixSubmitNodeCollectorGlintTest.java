/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the capture turns a single-pass glint back into the glint layer 1.21.1 drew.
 *
 * <p>26.3 draws a glinted surface in one pipeline with the glint on a second sampler.
 * The capture records the surface under its own layer and the same geometry again under
 * the glint pass, a layer of the glint texture alone, opened right after its surface so
 * that its ordinal, and so its shell, is above the surface's. These tests run the
 * collector's submits into a real provider and read back the layers the submitter gets.
 */
class RemixSubmitNodeCollectorGlintTest {

    private static final int LIGHT = 0x00F000F0;
    private static final int[] NO_TINTS = new int[0];
    private static final Identifier ARMOR_LAYER =
            Identifier.withDefaultNamespace("textures/entity/equipment/humanoid/diamond.png");

    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final RemixSubmitNodeCollector collector = new RemixSubmitNodeCollector(provider);

    @BeforeEach
    void beginCapture() {
        EntityRenderContext.get().begin(true);
        provider.beginEntity();
    }

    @AfterEach
    void clearRenderContext() {
        EntityRenderContext.get().reset();
    }

    /** One block-model face on the items atlas: not an item card, so it is captured as is. */
    private static BakedQuad itemQuad(RenderType surface, RenderType glint, RenderType specialGlint, float z) {
        BakedQuad.MaterialInfo material = new BakedQuad.MaterialInfo(
                null, null, surface, glint, specialGlint, -1, null, 0);
        return new BakedQuad(
                new Vector3f(0, 0, z), new Vector3f(1, 0, z), new Vector3f(1, 1, z), new Vector3f(0, 1, z),
                UVPair.pack(0.25f, 0.5f), UVPair.pack(0.375f, 0.5f),
                UVPair.pack(0.375f, 0.625f), UVPair.pack(0.25f, 0.625f),
                Direction.SOUTH, material);
    }

    private static BakedQuad cutoutItemQuad(float z) {
        Identifier atlas = TextureAtlas.LOCATION_ITEMS;
        return itemQuad(RenderTypes.itemCutout(atlas), RenderTypes.itemCutoutGlint(atlas),
                RenderTypes.itemCutoutGlintSpecial(atlas), z);
    }

    private static BakedQuad translucentBlockItemQuad(float z) {
        Identifier atlas = TextureAtlas.LOCATION_BLOCKS;
        return itemQuad(RenderTypes.itemTranslucent(atlas), RenderTypes.itemTranslucentGlint(atlas),
                RenderTypes.itemTranslucentGlintSpecial(atlas), z);
    }

    private void submitItem(ItemStackRenderState.FoilType foil, BakedQuad... quads) {
        List<BakedQuad> all = List.of(quads);
        collector.submitItem(new PoseStack(), ItemDisplayContext.GROUND, LIGHT, OverlayTexture.NO_OVERLAY, 0,
                NO_TINTS, new ItemQuads(all, all, List.of()), foil);
    }

    private static ModelPart cubePart() {
        ModelPart.Cube cube = new ModelPart.Cube(0, 0, -4, 0, -4, 8, 8, 8, 0, 0, 0, false, 64, 32,
                EnumSet.allOf(Direction.class));
        return new ModelPart(List.of(cube), Map.of());
    }

    @Test
    void aFoilItemQuadIsCapturedAgainIntoAGlintLayerOverItsSurface() {
        submitItem(ItemStackRenderState.FoilType.STANDARD, cutoutItemQuad(0.5f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(2, layers.size());
        RemixVertexConsumerProvider.CapturedLayer surface = layers.get(0);
        RemixVertexConsumerProvider.CapturedLayer glint = layers.get(1);

        assertSame(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS), surface.renderLayer());
        assertEquals(TextureAtlas.LOCATION_ITEMS, surface.textureId());
        assertSame(RenderTypes.patternedShieldGlint(), glint.renderLayer());
        assertEquals(RenderLayerUtils.ITEM_GLINT_TEXTURE, glint.textureId());
        // Additive over the surface, not an opaque purple card painted on it.
        assertEquals(RenderLayerUtils.SurfaceMode.GLINT, RenderLayerUtils.surfaceMode(glint.renderLayer()));
        assertEquals(RenderLayerUtils.SurfaceMode.CUTOUT, RenderLayerUtils.surfaceMode(surface.renderLayer()));
        assertTrue(glint.layerIndex() > surface.layerIndex(), "the glint stands a shell over its surface");

        // The same quad, vertex for vertex: position, the surface's own texture coordinate
        // (the glint's scroll is a texture matrix that never reached a vertex), colour, pivot.
        assertSameGeometry(surface, glint);
        assertEquals(1, glint.consumer().getPartCount());
    }

    @Test
    void anItemWithoutFoilHasNoGlintLayer() {
        submitItem(ItemStackRenderState.FoilType.NONE, cutoutItemQuad(0.5f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(1, layers.size());
        assertSame(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS), layers.getFirst().renderLayer());
    }

    /** A compass or clock's special foil projects a decal coordinate in vanilla; the capture keeps the quad's own. */
    @Test
    void aSpecialFoilIsCapturedLikeAStandardOne() {
        submitItem(ItemStackRenderState.FoilType.SPECIAL, cutoutItemQuad(0.5f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(2, layers.size());
        assertSame(RenderTypes.patternedShieldGlint(), layers.get(1).renderLayer());
        assertSameGeometry(layers.get(0), layers.get(1));
    }

    /**
     * Each glinted surface has its own glint layer, opened right after it, so a second
     * surface drawn between them cannot end up over its own glint.
     */
    @Test
    void everyGlintedSurfaceHasItsOwnGlintLayerAboveIt() {
        submitItem(ItemStackRenderState.FoilType.STANDARD, cutoutItemQuad(0.5f), translucentBlockItemQuad(0.25f));
        // A second foil item on the first surface shares that surface's glint layer.
        submitItem(ItemStackRenderState.FoilType.STANDARD, cutoutItemQuad(0.75f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(4, layers.size());
        assertSame(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS), layers.get(0).renderLayer());
        assertSame(RenderTypes.patternedShieldGlint(), layers.get(1).renderLayer());
        assertSame(RenderTypes.itemTranslucent(TextureAtlas.LOCATION_BLOCKS), layers.get(2).renderLayer());
        assertSame(RenderTypes.patternedShieldGlint(), layers.get(3).renderLayer());
        for (int i = 0; i < layers.size(); i++) {
            assertEquals(i, layers.get(i).layerIndex());
        }
        assertNotSame(layers.get(1).consumer(), layers.get(3).consumer());
        assertEquals(8, layers.get(0).vertexCount());
        assertEquals(8, layers.get(1).vertexCount());
        assertEquals(4, layers.get(2).vertexCount());
        assertEquals(4, layers.get(3).vertexCount());
    }

    /** A surface that was first drawn without foil keeps its ordinal; its glint opens after it. */
    @Test
    void aGlintOpensAfterItsSurfaceEvenWhenTheSurfaceCameFirstWithoutFoil() {
        submitItem(ItemStackRenderState.FoilType.NONE, cutoutItemQuad(0.5f));
        submitItem(ItemStackRenderState.FoilType.NONE, translucentBlockItemQuad(0.25f));
        submitItem(ItemStackRenderState.FoilType.STANDARD, cutoutItemQuad(0.75f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(3, layers.size());
        assertEquals(0, layers.get(0).layerIndex());
        assertSame(RenderTypes.patternedShieldGlint(), layers.get(2).renderLayer());
        assertEquals(2, layers.get(2).layerIndex());
        assertEquals(4, layers.get(2).vertexCount());
    }

    /**
     * Vanilla still submits two glints as a pass of their own (the patterned shield's,
     * the trimmed armour's). That pass is an ordinary layer, apart from any glint the
     * capture adds over a surface.
     */
    @Test
    void aGlintPassVanillaSubmitsItselfIsALayerOfItsOwn() {
        ModelPart part = cubePart();
        collector.submitModelPart(part, new PoseStack(), RenderTypes.patternedShieldGlint(), LIGHT,
                OverlayTexture.NO_OVERLAY, null, -1, 0);
        submitItem(ItemStackRenderState.FoilType.STANDARD, cutoutItemQuad(0.5f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(3, layers.size());
        assertSame(RenderTypes.patternedShieldGlint(), layers.get(0).renderLayer());
        assertSame(RenderTypes.patternedShieldGlint(), layers.get(2).renderLayer());
        assertNotSame(layers.get(0).consumer(), layers.get(2).consumer());
        assertEquals(24, layers.get(0).vertexCount());
        assertEquals(4, layers.get(2).vertexCount());
        // Asking for the pass by name again finds vanilla's own layer, not the item's glint.
        assertSame(layers.get(0).consumer(), provider.getBuffer(RenderTypes.patternedShieldGlint()));
    }

    /**
     * A glinted model (enchanted armour's first layer, a trident, a plain shield) is
     * compiled a second time into its glint layer, untinted and without overlay, as
     * 1.21.1's {@code renderGlint} drew it.
     */
    @Test
    void aGlintedModelIsCompiledAgainIntoItsGlintLayerUntintedAndWithoutOverlay() {
        int dye = 0xFF3366CC;
        int hurt = OverlayTexture.pack(0, true);
        collector.submitModelPart(cubePart(), new PoseStack(), RenderTypes.armorCutoutNoCullGlint(ARMOR_LAYER),
                LIGHT, hurt, null, dye, 0);

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(2, layers.size());
        RemixVertexConsumerProvider.CapturedLayer surface = layers.get(0);
        RemixVertexConsumerProvider.CapturedLayer glint = layers.get(1);
        assertEquals(ARMOR_LAYER, surface.textureId());
        assertSame(RenderTypes.trimmedArmorGlint(), glint.renderLayer());
        assertEquals(RenderLayerUtils.ARMOR_GLINT_TEXTURE, glint.textureId());
        assertEquals(1, glint.layerIndex());
        // Enchanted armour adds a shimmer instead of becoming a near-black opaque shell.
        assertEquals(RenderLayerUtils.SurfaceMode.GLINT, RenderLayerUtils.surfaceMode(glint.renderLayer()));

        int count = surface.vertexCount();
        assertEquals(24, count);
        assertEquals(count, glint.vertexCount());
        RemixCapturingConsumer s = surface.consumer();
        RemixCapturingConsumer g = glint.consumer();
        for (int i = 0; i < count; i++) {
            assertEquals(s.getPx()[i], g.getPx()[i]);
            assertEquals(s.getPy()[i], g.getPy()[i]);
            assertEquals(s.getPz()[i], g.getPz()[i]);
            assertEquals(s.getTu()[i], g.getTu()[i]);
            assertEquals(s.getTv()[i], g.getTv()[i]);
            assertEquals(RemixCapturingConsumer.applyEntityOverlay(dye, hurt), s.getColors()[i]);
            assertEquals(0xFFFFFFFF, g.getColors()[i]);
        }
    }

    @Test
    void aModelWithoutAGlintSamplerIsCompiledOnce() {
        collector.submitModelPart(cubePart(), new PoseStack(), RenderTypes.armorCutoutNoCull(ARMOR_LAYER),
                LIGHT, OverlayTexture.NO_OVERLAY, null, -1, 0);

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(1, layers.size());
        assertNull(provider.getGlintBuffer(RenderTypes.armorCutoutNoCull(ARMOR_LAYER),
                RenderTypes.armorCutoutNoCull(ARMOR_LAYER)));
    }

    private static void assertSameGeometry(RemixVertexConsumerProvider.CapturedLayer expected,
                                           RemixVertexConsumerProvider.CapturedLayer actual) {
        int count = expected.vertexCount();
        assertEquals(count, actual.vertexCount());
        RemixCapturingConsumer e = expected.consumer();
        RemixCapturingConsumer a = actual.consumer();
        assertArrayEquals(slice(e.getPx(), count), slice(a.getPx(), count));
        assertArrayEquals(slice(e.getPy(), count), slice(a.getPy(), count));
        assertArrayEquals(slice(e.getPz(), count), slice(a.getPz(), count));
        assertArrayEquals(slice(e.getTu(), count), slice(a.getTu(), count));
        assertArrayEquals(slice(e.getTv(), count), slice(a.getTv(), count));
        // Each consumer registers the pose it sees as a bone of its own; the two bones hold one pivot.
        List<org.joml.Matrix4f> bones = EntityRenderContext.get().getBoneTransforms();
        for (int i = 0; i < count; i++) {
            assertEquals(e.getColors()[i], a.getColors()[i]);
            assertEquals(bones.get(e.getBoneIndices()[i]), bones.get(a.getBoneIndices()[i]));
        }
    }

    private static float[] slice(float[] values, int count) {
        float[] out = new float[count];
        System.arraycopy(values, 0, out, 0, count);
        return out;
    }
}
