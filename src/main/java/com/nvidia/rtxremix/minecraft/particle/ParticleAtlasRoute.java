/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.InstanceCategoryBit;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * Which Remix material a quad particle is drawn with, chosen from the atlas its
 * {@code SingleQuadParticle.Layer} samples. Vanilla's layers name three atlases
 * ({@code SingleQuadParticle.Layer}: {@code OPAQUE/TRANSLUCENT} on the particle
 * atlas, {@code *_TERRAIN} on the block atlas, {@code *_ITEMS} on the item atlas),
 * and {@code Layer.bySprite} picks among them by the sprite's own atlas, so a
 * breaking-item crumb of a block item lands on the block route and one of a plain
 * item on the item route.
 *
 * <ul>
 *   <li>{@link #PARTICLES}: the particle atlas material, {@code PARTICLE}
 *       instance category, alpha-blended draw state (1.21.1 {@code PARTICLE_SHEET_*}).</li>
 *   <li>{@link #BLOCKS}: the block atlas cutout material, no category,
 *       alpha-tested draw state (1.21.1 {@code TERRAIN_SHEET}).</li>
 *   <li>{@link #ITEMS}: the item atlas cutout material, drawn like {@link #BLOCKS}.
 *       Item crumbs were {@code TERRAIN_SHEET} particles in 1.21.1, when items still
 *       lived on the block atlas, so they keep that route's category and draw state.</li>
 * </ul>
 *
 * Any other atlas, such as one a mod stitches, has no Remix material and is
 * {@link #NONE}: not drawn.
 */
final class ParticleAtlasRoute {

    static final int NONE = -1;
    static final int PARTICLES = 0;
    static final int BLOCKS = 1;
    static final int ITEMS = 2;
    /** Number of drawable routes; every route other than {@link #NONE} is below it. */
    static final int COUNT = 3;

    /** Alpha-blended draw state ({@code textureTimesVertexColorAlphaBlend}). */
    static final int BLEND_ALPHA = 1;
    /** Alpha-tested draw state ({@code textureTimesVertexColorAlphaTest(128)}), as cutout terrain. */
    static final int BLEND_ALPHA_TEST = 2;

    private ParticleAtlasRoute() {
    }

    /** The route for a layer's {@code textureAtlasLocation()}, or {@link #NONE}. Allocation-free. */
    static int of(Identifier atlas) {
        if (atlas == null) return NONE;
        if (TextureAtlas.LOCATION_PARTICLES.equals(atlas)) return PARTICLES;
        if (TextureAtlas.LOCATION_BLOCKS.equals(atlas)) return BLOCKS;
        if (TextureAtlas.LOCATION_ITEMS.equals(atlas)) return ITEMS;
        return NONE;
    }

    /** Instance category flags a route's draw carries. */
    static int categoryFlags(int route) {
        return route == PARTICLES ? InstanceCategoryBit.PARTICLE : 0;
    }

    /** Draw state a route's draw carries, {@link #BLEND_ALPHA} or {@link #BLEND_ALPHA_TEST}. */
    static int blendKind(int route) {
        return route == PARTICLES ? BLEND_ALPHA : BLEND_ALPHA_TEST;
    }
}
