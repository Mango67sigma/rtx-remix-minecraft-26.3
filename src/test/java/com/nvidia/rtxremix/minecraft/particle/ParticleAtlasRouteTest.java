/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ParticleAtlasRouteTest {

    @Test
    void eachVanillaParticleAtlasHasItsOwnRoute() {
        assertEquals(ParticleAtlasRoute.PARTICLES, ParticleAtlasRoute.of(TextureAtlas.LOCATION_PARTICLES));
        assertEquals(ParticleAtlasRoute.BLOCKS, ParticleAtlasRoute.of(TextureAtlas.LOCATION_BLOCKS));
        assertEquals(ParticleAtlasRoute.ITEMS, ParticleAtlasRoute.of(TextureAtlas.LOCATION_ITEMS));
    }

    @Test
    void everyVanillaQuadLayerIsDrawn() {
        assertEquals(ParticleAtlasRoute.PARTICLES, routeOf(SingleQuadParticle.Layer.OPAQUE));
        assertEquals(ParticleAtlasRoute.PARTICLES, routeOf(SingleQuadParticle.Layer.TRANSLUCENT));
        assertEquals(ParticleAtlasRoute.BLOCKS, routeOf(SingleQuadParticle.Layer.OPAQUE_TERRAIN));
        assertEquals(ParticleAtlasRoute.BLOCKS, routeOf(SingleQuadParticle.Layer.TRANSLUCENT_TERRAIN));
        assertEquals(ParticleAtlasRoute.ITEMS, routeOf(SingleQuadParticle.Layer.OPAQUE_ITEMS));
        assertEquals(ParticleAtlasRoute.ITEMS, routeOf(SingleQuadParticle.Layer.TRANSLUCENT_ITEMS));
    }

    @Test
    void anItemSpriteCrumbTakesTheItemRoute() {
        // BreakingItemParticle picks its layer with Layer.bySprite(sprite), from the
        // sprite's own atlas: an item sprite lands on the item atlas route.
        assertEquals(ParticleAtlasRoute.ITEMS,
                routeOf(SingleQuadParticle.Layer.bySprite(sprite(TextureAtlas.LOCATION_ITEMS))));
        // A block item's particle material lives on the block atlas and stays on its route.
        assertEquals(ParticleAtlasRoute.BLOCKS,
                routeOf(SingleQuadParticle.Layer.bySprite(sprite(TextureAtlas.LOCATION_BLOCKS))));
    }

    @Test
    void routingComparesTheAtlasIdByValue() {
        // A mod layer may build its own Identifier for a vanilla atlas.
        assertEquals(ParticleAtlasRoute.ITEMS,
                ParticleAtlasRoute.of(Identifier.withDefaultNamespace("textures/atlas/items.png")));
        assertEquals(ParticleAtlasRoute.BLOCKS,
                ParticleAtlasRoute.of(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")));
    }

    @Test
    void atlasesWithoutARemixMaterialAreNotDrawn() {
        assertEquals(ParticleAtlasRoute.NONE, ParticleAtlasRoute.of(null));
        assertEquals(ParticleAtlasRoute.NONE,
                ParticleAtlasRoute.of(Identifier.fromNamespaceAndPath("examplemod", "textures/atlas/particles.png")));
        assertEquals(ParticleAtlasRoute.NONE,
                ParticleAtlasRoute.of(Identifier.withDefaultNamespace("textures/atlas/gui.png")));
    }

    @Test
    void itemCrumbsDrawLikeTerrainCrumbs() {
        // 1.21.1 drew item crumbs as TERRAIN_SHEET particles: no instance category and
        // the alpha-tested draw state that pairs with a cutout atlas material.
        assertEquals(0, ParticleAtlasRoute.categoryFlags(ParticleAtlasRoute.ITEMS));
        assertEquals(ParticleAtlasRoute.categoryFlags(ParticleAtlasRoute.BLOCKS),
                ParticleAtlasRoute.categoryFlags(ParticleAtlasRoute.ITEMS));
        assertEquals(ParticleAtlasRoute.BLEND_ALPHA_TEST, ParticleAtlasRoute.blendKind(ParticleAtlasRoute.ITEMS));
        assertEquals(ParticleAtlasRoute.BLEND_ALPHA_TEST, ParticleAtlasRoute.blendKind(ParticleAtlasRoute.BLOCKS));
    }

    @Test
    void particleAtlasQuadsKeepTheParticleCategoryAndBlending() {
        assertEquals(InstanceCategoryBit.PARTICLE, ParticleAtlasRoute.categoryFlags(ParticleAtlasRoute.PARTICLES));
        assertEquals(ParticleAtlasRoute.BLEND_ALPHA, ParticleAtlasRoute.blendKind(ParticleAtlasRoute.PARTICLES));
    }

    @Test
    void routesIndexTheirPerRouteArrays() {
        assertEquals(3, ParticleAtlasRoute.COUNT);
        for (int route : new int[] {ParticleAtlasRoute.PARTICLES, ParticleAtlasRoute.BLOCKS, ParticleAtlasRoute.ITEMS}) {
            assertEquals(true, route >= 0 && route < ParticleAtlasRoute.COUNT, "route " + route);
        }
    }

    @Test
    void theTextureManagerCapturesTheAtlasesTheTerrainAndItemRoutesSample() {
        assertEquals(TextureAtlas.LOCATION_BLOCKS, BlockTextureManager.BLOCK_ATLAS_ID);
        assertEquals(TextureAtlas.LOCATION_ITEMS, BlockTextureManager.ITEM_ATLAS_ID);
    }

    @Test
    void anUninitialisedTextureManagerHasNoItemMaterial() {
        assertSame(null, new BlockTextureManager(null).getItemAtlasMaterial());
    }

    private static int routeOf(SingleQuadParticle.Layer layer) {
        return ParticleAtlasRoute.of(layer.textureAtlasLocation());
    }

    private static TextureAtlasSprite sprite(Identifier atlas) {
        SpriteContents contents = new SpriteContents(
                Identifier.withDefaultNamespace("item/test"),
                new FrameSize(16, 16),
                new NativeImage(16, 16, false));
        // The constructor is protected; an anonymous subclass reaches it.
        TextureAtlasSprite sprite = new TextureAtlasSprite(atlas, contents, 256, 256, 0, 0, 1) {};
        contents.close();
        return sprite;
    }
}
