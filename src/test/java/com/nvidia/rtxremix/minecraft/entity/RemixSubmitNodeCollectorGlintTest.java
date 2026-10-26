/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.platform.NativeImage;
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
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the capture records a glinted surface: once, under the glinted render type
 * vanilla draws it with, exactly as its plain counterpart would be recorded, so that the
 * submitter can give that layer the glint as the surface's own emission. Nothing is
 * captured in front of the surface; a separate glint layer would hide it from the path
 * tracer. These tests run the collector's submits into a real provider and read back the
 * layers the submitter gets.
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

    private static BakedQuad.MaterialInfo itemMaterial(TextureAtlasSprite sprite, Identifier atlas, boolean translucent) {
        return translucent
                ? new BakedQuad.MaterialInfo(sprite, null, RenderTypes.itemTranslucent(atlas),
                        RenderTypes.itemTranslucentGlint(atlas), RenderTypes.itemTranslucentGlintSpecial(atlas), -1, null, 0)
                : new BakedQuad.MaterialInfo(sprite, null, RenderTypes.itemCutout(atlas),
                        RenderTypes.itemCutoutGlint(atlas), RenderTypes.itemCutoutGlintSpecial(atlas), -1, null, 0);
    }

    /** One block-model face: not an item card, so it is captured as is. */
    private static BakedQuad itemQuad(BakedQuad.MaterialInfo material, float z) {
        return new BakedQuad(
                new Vector3f(0, 0, z), new Vector3f(1, 0, z), new Vector3f(1, 1, z), new Vector3f(0, 1, z),
                UVPair.pack(0.25f, 0.5f), UVPair.pack(0.375f, 0.5f),
                UVPair.pack(0.375f, 0.625f), UVPair.pack(0.25f, 0.625f),
                Direction.SOUTH, material);
    }

    private static BakedQuad cutoutItemQuad(float z) {
        return itemQuad(itemMaterial(null, TextureAtlas.LOCATION_ITEMS, false), z);
    }

    private static BakedQuad translucentBlockItemQuad(float z) {
        return itemQuad(itemMaterial(null, TextureAtlas.LOCATION_BLOCKS, true), z);
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
    void aFoilItemQuadIsCapturedOnceUnderItsGlintedRenderType() {
        submitItem(ItemStackRenderState.FoilType.STANDARD, cutoutItemQuad(0.5f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(1, layers.size(), "no second layer stands in front of the surface");
        RemixVertexConsumerProvider.CapturedLayer layer = layers.getFirst();
        assertSame(RenderTypes.itemCutoutGlint(TextureAtlas.LOCATION_ITEMS), layer.renderLayer());
        assertEquals(TextureAtlas.LOCATION_ITEMS, layer.textureId());
        assertEquals(RenderLayerUtils.SurfaceMode.CUTOUT, RenderLayerUtils.surfaceMode(layer.renderLayer()));
        assertSame(RenderLayerUtils.Glint.ITEM, RenderLayerUtils.glint(layer.renderLayer()));
        assertEquals(4, layer.vertexCount());
    }

    @Test
    void anItemWithoutFoilKeepsItsPlainRenderType() {
        submitItem(ItemStackRenderState.FoilType.NONE, cutoutItemQuad(0.5f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(1, layers.size());
        assertSame(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS), layers.getFirst().renderLayer());
        assertNull(RenderLayerUtils.glint(layers.getFirst().renderLayer()));
    }

    /** A compass or clock's special foil projects a decal coordinate in vanilla; the capture keeps the quad's own. */
    @Test
    void aSpecialFoilIsCapturedUnderItsSpecialGlintType() {
        submitItem(ItemStackRenderState.FoilType.SPECIAL, cutoutItemQuad(0.5f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(1, layers.size());
        assertSame(RenderTypes.itemCutoutGlintSpecial(TextureAtlas.LOCATION_ITEMS), layers.getFirst().renderLayer());
        assertSame(RenderLayerUtils.Glint.ITEM, RenderLayerUtils.glint(layers.getFirst().renderLayer()));
    }

    /**
     * A foil item and a plain one land on layers of their own, so that the glinted layer
     * alone gets the glinted material; foil items on one surface share their layer.
     */
    @Test
    void plainAndFoilItemsLandOnLayersOfTheirOwn() {
        submitItem(ItemStackRenderState.FoilType.NONE, cutoutItemQuad(0.5f), translucentBlockItemQuad(0.25f));
        submitItem(ItemStackRenderState.FoilType.STANDARD, cutoutItemQuad(0.75f), translucentBlockItemQuad(0.125f));
        submitItem(ItemStackRenderState.FoilType.STANDARD, cutoutItemQuad(0.875f));

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(4, layers.size());
        assertSame(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS), layers.get(0).renderLayer());
        assertSame(RenderTypes.itemTranslucent(TextureAtlas.LOCATION_BLOCKS), layers.get(1).renderLayer());
        assertSame(RenderTypes.itemCutoutGlint(TextureAtlas.LOCATION_ITEMS), layers.get(2).renderLayer());
        assertSame(RenderTypes.itemTranslucentGlint(TextureAtlas.LOCATION_BLOCKS), layers.get(3).renderLayer());
        for (int i = 0; i < layers.size(); i++) {
            assertEquals(i, layers.get(i).layerIndex());
        }
        assertEquals(4, layers.get(0).vertexCount());
        assertEquals(4, layers.get(1).vertexCount());
        assertEquals(8, layers.get(2).vertexCount());
        assertEquals(4, layers.get(3).vertexCount());
    }

    /**
     * An enchanted flat item is the same card as its plain counterpart, rebuilt from the
     * sprite's opaque texels, with the same texture coordinates: the glint changes the
     * material, never the geometry.
     */
    @Test
    void aFoilItemCardIsRebuiltExactlyLikeItsPlainCounterpart() {
        SpriteContents contents = spriteContents(
                "........", "..####..", "..####..", "...##...", "...##...", "...##...", "...##...", "...##...");
        try {
            TextureAtlasSprite sprite = new TextureAtlasSprite(TextureAtlas.LOCATION_ITEMS, contents, 64, 64, 16, 32, 0) {};
            BakedQuad.MaterialInfo material = itemMaterial(sprite, TextureAtlas.LOCATION_ITEMS, false);
            BakedQuad front = cardFace(sprite, material, 7.5f / 16, Direction.NORTH, false);
            BakedQuad back = cardFace(sprite, material, 8.5f / 16, Direction.SOUTH, true);
            submitItem(ItemStackRenderState.FoilType.NONE, front, back);
            submitItem(ItemStackRenderState.FoilType.STANDARD, front, back);

            List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
            assertEquals(2, layers.size());
            RemixVertexConsumerProvider.CapturedLayer plain = layers.get(0);
            RemixVertexConsumerProvider.CapturedLayer glinted = layers.get(1);
            assertSame(RenderTypes.itemCutoutGlint(TextureAtlas.LOCATION_ITEMS), glinted.renderLayer());
            // Two opaque rectangles per face, the empty margins dropped, in both layers.
            assertEquals(16, plain.vertexCount());
            assertSameGeometry(plain, glinted);
            assertEquals(RemixCapturingConsumer.NO_VERTICES, glinted.consumer().cardFaceOfPartStartingAt(0),
                    "the glinted card keeps no full face either");
        } finally {
            contents.close();
        }
    }

    /**
     * A glinted model (enchanted armour's first layer, a trident, a plain shield) is
     * compiled once, under its own render type, with its dye and overlay, as its plain
     * counterpart would be.
     */
    @Test
    void aGlintedModelIsCompiledOnceUnderItsOwnRenderType() {
        int dye = 0xFF3366CC;
        int hurt = OverlayTexture.pack(0, true);
        collector.submitModelPart(cubePart(), new PoseStack(), RenderTypes.armorCutoutNoCullGlint(ARMOR_LAYER),
                LIGHT, hurt, null, dye, 0);

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        assertEquals(1, layers.size());
        RemixVertexConsumerProvider.CapturedLayer layer = layers.getFirst();
        assertSame(RenderTypes.armorCutoutNoCullGlint(ARMOR_LAYER), layer.renderLayer());
        assertEquals(ARMOR_LAYER, layer.textureId());
        assertEquals(RenderLayerUtils.SurfaceMode.CUTOUT, RenderLayerUtils.surfaceMode(layer.renderLayer()));
        assertSame(RenderLayerUtils.Glint.ARMOR, RenderLayerUtils.glint(layer.renderLayer()));
        assertEquals(24, layer.vertexCount());
        for (int i = 0; i < 24; i++) {
            assertEquals(RemixCapturingConsumer.applyEntityOverlay(dye, hurt), layer.consumer().getColors()[i]);
        }
    }

    /** The patterned shield's and the trimmed armour's glint passes reach no layer. */
    @Test
    void vanillasSeparateGlintPassesAreNotCaptured() {
        collector.submitModelPart(cubePart(), new PoseStack(), RenderTypes.patternedShieldGlint(), LIGHT,
                OverlayTexture.NO_OVERLAY, null, -1, 0);
        collector.submitModelPart(cubePart(), new PoseStack(), RenderTypes.trimmedArmorGlint(), LIGHT,
                OverlayTexture.NO_OVERLAY, null, -1, 0);

        assertTrue(provider.endEntity().isEmpty());
    }

    /** An 8x8 sprite whose {@code '#'} texels are opaque and the rest alpha 0. */
    private static SpriteContents spriteContents(String... rows) {
        NativeImage image = new NativeImage(8, 8, false);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) image.setPixel(x, y, rows[y].charAt(x) == '#' ? 0xFF808080 : 0);
        }
        return new SpriteContents(Identifier.fromNamespaceAndPath("minecraft", "item/glint_test"),
                new FrameSize(8, 8), image);
    }

    /** A full card face of a flat item, as {@code ItemModelGenerator} bakes it. */
    private static BakedQuad cardFace(TextureAtlasSprite sprite, BakedQuad.MaterialInfo material, float z,
                                      Direction face, boolean mirroredU) {
        float[][] corners = {{0, 1}, {0, 0}, {1, 0}, {1, 1}};
        Vector3f[] positions = new Vector3f[4];
        long[] uvs = new long[4];
        float span = sprite.getU1() - sprite.getU0();
        for (int v = 0; v < 4; v++) {
            float x = corners[v][0], y = corners[v][1];
            positions[v] = new Vector3f(x, y, z);
            uvs[v] = UVPair.pack(
                    mirroredU ? sprite.getU1() - span * x : sprite.getU0() + span * x,
                    sprite.getV0() + (sprite.getV1() - sprite.getV0()) * (1 - y));
        }
        return new BakedQuad(positions[0], positions[1], positions[2], positions[3],
                uvs[0], uvs[1], uvs[2], uvs[3], face, material);
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
