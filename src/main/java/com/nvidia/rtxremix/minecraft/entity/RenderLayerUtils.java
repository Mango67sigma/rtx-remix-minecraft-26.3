/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;

/**
 * Reads the layer-level facts the capture path needs from a {@link RenderType}.
 *
 * <p>A renderer submits its geometry by layer,
 * {@code collector.submitModel(model, state, poseStack, RenderTypes.entityCutout(textureId), ...)},
 * so the layer is where geometry and material meet and the one place material
 * identity is read. Its {@link RenderSetup}, reached through the access widener,
 * names the texture the layer is submitted under, if any, and its
 * {@link RenderPipeline} gives the {@link SurfaceMode} Remix shades with, whether the
 * geometry is streamed afresh every frame, the {@link Topology}, and whether an
 * untextured layer carries geometry worth submitting at all.
 *
 * <p>Minecraft 26.3 has no render phases any more: what {@code RenderStateShard}
 * used to say about a layer's shader program, transparency and depth test is now
 * the pipeline's identity, its colour target blend function and its depth-stencil
 * state. The verdicts below are the 1.21.1 ones re-derived from those.
 */
final class RenderLayerUtils {

    /** Material behavior encoded by Minecraft's actual render pipelines, and clear glass. */
    enum SurfaceMode {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT,
        EMISSIVE,
        /**
         * Clear glass on the block atlas: blended, through the refracting material
         * terrain gives placed clear glass. No pipeline encodes it, since vanilla
         * draws clear glass as a cutout; {@code RemixEntitySubmitter.atlasSurfaceMode}
         * assigns it to geometry from a clear-glass block item.
         */
        GLASS
    }

    /**
     * The enchantment glint a single-pass glint render type adds over its surface: the
     * glint texture it samples and the scale of the texture transform it samples it
     * through. Vanilla's glint coordinate is the surface's own texture coordinate scaled
     * by {@link #scale}, turned by {@link #ROTATION} and scrolled with wall-clock time
     * ({@code TextureTransform.setupGlintTexturing}); the shader then adds the square of
     * the texel it finds to the lit surface colour. The scales are spelled out because
     * vanilla keeps them inside the transforms' private matrix suppliers.
     */
    enum Glint {
        /** {@code TextureTransform.GLINT_TEXTURING}: items, on either atlas. */
        ITEM(ITEM_GLINT_TEXTURE, 8.0f),
        /** {@code TextureTransform.ENTITY_GLINT_TEXTURING}: tridents and plain shields. */
        ENTITY(ITEM_GLINT_TEXTURE, 0.5f),
        /** {@code TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING}: armour and elytra. */
        ARMOR(ARMOR_GLINT_TEXTURE, 0.16f);

        /** The turn every glint transform gives the scaled coordinate, pi/18. */
        static final float ROTATION = (float) (Math.PI / 18);

        final Identifier texture;
        final float scale;

        Glint(Identifier texture, float scale) {
            this.texture = texture;
            this.scale = scale;
        }
    }

    /** How a layer's captured vertex stream turns into Remix triangles. */
    enum Topology {
        QUADS,
        TRIANGLE_STRIP
    }

    /**
     * Material identity for layers that draw without a texture. They have no
     * {@link Identifier} of their own, but the submitter keys materials and mesh
     * pools by one, so they share this.
     */
    static final Identifier UNTEXTURED_LAYER_ID =
            Identifier.fromNamespaceAndPath("rtxremix", "untextured_entity_layer");

    /** The sampler every single-texture render type binds its texture to. */
    private static final String PRIMARY_SAMPLER = "Sampler0";
    /**
     * The sampler a single-pass glint render type binds the glint texture to, beside its
     * surface texture on {@link #PRIMARY_SAMPLER} ({@code RenderTypes.itemCutoutGlint},
     * {@code entitySolidGlint}, {@code armorCutoutNoCullGlint} and their variants).
     */
    private static final String GLINT_SAMPLER = "GlintSampler";
    /** The vertex attribute a layer must carry to be shaded by vertex colour alone. */
    private static final String COLOR_ATTRIBUTE = "Color";

    /**
     * Vanilla's item and armour glint textures, {@code ItemFeatureRenderer.ENCHANTED_GLINT_ITEM}
     * and {@code ENCHANTED_GLINT_ARMOR}. Spelled out rather than read from the renderer so
     * that {@link Glint} loads no renderer class.
     */
    static final Identifier ITEM_GLINT_TEXTURE =
            Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png");
    static final Identifier ARMOR_GLINT_TEXTURE =
            Identifier.withDefaultNamespace("textures/misc/enchanted_glint_armor.png");

    private RenderLayerUtils() {}

    /**
     * Cache of RenderType -> capturable texture. Most RenderType instances are memoized by
     * vanilla, but weak keys avoid retaining dynamic layers created by mods or skin features.
     */
    private static final Map<RenderType, Optional<Identifier>> capturableTextureCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Cache of RenderType -> streamed-per-frame verdict, weak-keyed like {@link #capturableTextureCache}. */
    private static final Map<RenderType, Boolean> streamedPerFrameCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Cache of RenderType -> the glint it adds over its surface, weak-keyed like {@link #capturableTextureCache}. */
    private static final Map<RenderType, Optional<Glint>> glintCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Returns the texture a captured layer is submitted under, or empty when Remix has
     * nothing to draw for the layer. Results are cached by layer identity.
     *
     * <p>Empty covers four cases. Untextured layers (shadow, debug, lines) carry no
     * material identity at all. Layers that pass the depth test unconditionally, the
     * see-through text of a name tag, are drawn over the whole scene in vanilla; no
     * placement in a ray-traced scene reproduces that, and vanilla draws the
     * depth-tested copy of the same text beside them, so their quads would only double
     * it. The end portal and gateway, whose two textures feed a screen-space effect
     * rather than a surface, have no texture Remix could paint them with. And vanilla's
     * separate glint passes, see {@link #isGlintPass}, have no surface of their own.
     *
     * @param layer the render type (typically from an entity renderer)
     * @return the texture identifier to capture under, or empty to skip the layer
     */
    static Optional<Identifier> capturableTexture(RenderType layer) {
        return capturableTextureCache.computeIfAbsent(layer, RenderLayerUtils::capturableTextureUncached);
    }

    private static Optional<Identifier> capturableTextureUncached(RenderType layer) {
        RenderPipeline pipeline = layer.pipeline();
        // An outline render type is vanilla's glow silhouette of a model, drawn into
        // the outline target over the whole scene; it carries no surface.
        if (layer.isOutline() || !depthTested(pipeline) || isScreenSpaceEffect(pipeline)
                || isGlintPass(pipeline)) {
            return Optional.empty();
        }
        return extractTexture(layer);
    }

    /**
     * The texture bound to the layer's primary sampler. A single-pass glint layer binds
     * the glint texture to a second sampler as well, which is not this layer's texture
     * but the glint it adds over it, see {@link #glint}.
     */
    private static Optional<Identifier> extractTexture(RenderType layer) {
        return boundTexture(layer, PRIMARY_SAMPLER);
    }

    private static Optional<Identifier> boundTexture(RenderType layer, String sampler) {
        RenderSetup.TextureBinding binding = setup(layer).textures.get(sampler);
        return binding == null ? Optional.empty() : Optional.of(binding.location());
    }

    /**
     * The glint {@code layer} adds over its surface, or null when it binds no glint.
     *
     * <p>Since 26.2 a glinted surface is drawn once, in a pipeline that samples the glint
     * on a second sampler and adds it in the shader, so the glint belongs to the surface
     * rather than to a layer of its own. The capture keeps it there: the layer is
     * captured like its plain counterpart, under the same texture and surface mode, and
     * the submitter bakes this glint into the emission of that surface's material.
     */
    static Glint glint(RenderType layer) {
        return glintCache.computeIfAbsent(layer, RenderLayerUtils::glintUncached).orElse(null);
    }

    private static Optional<Glint> glintUncached(RenderType layer) {
        if (layer.isOutline()) {
            return Optional.empty();
        }
        return boundTexture(layer, GLINT_SAMPLER)
                .map(texture -> glintFor(setup(layer).textureTransform, texture));
    }

    /**
     * The glint of a layer whose glint sampler binds {@code samplerTexture} through
     * {@code transform}. Vanilla pairs each of its three glint transforms with one glint
     * texture; a mod's own transform takes the glint of its texture, and a mod's own
     * texture the item glint, since only vanilla's two textures are baked.
     */
    static Glint glintFor(TextureTransform transform, Identifier samplerTexture) {
        if (transform == TextureTransform.GLINT_TEXTURING) return Glint.ITEM;
        if (transform == TextureTransform.ENTITY_GLINT_TEXTURING) return Glint.ENTITY;
        if (transform == TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING) return Glint.ARMOR;
        return ARMOR_GLINT_TEXTURE.equals(samplerTexture) ? Glint.ARMOR : Glint.ITEM;
    }

    private static RenderSetup setup(RenderType layer) {
        return layer.state;
    }

    /**
     * Whether the pipeline tests depth at all. A pipeline built without a depth-stencil
     * state, or with one that always passes, draws over the whole scene.
     */
    static boolean depthTested(RenderPipeline pipeline) {
        DepthStencilState depth = pipeline.getDepthStencilState();
        return depth != null && depth.depthTest() != CompareOp.ALWAYS_PASS;
    }

    private static boolean isScreenSpaceEffect(RenderPipeline pipeline) {
        return pipeline == RenderPipelines.END_PORTAL || pipeline == RenderPipelines.END_GATEWAY;
    }

    /**
     * Whether the pipeline is one of vanilla's separate glint passes: a model drawn a
     * second time over what is already on screen, with the glint texture alone, blended
     * with {@code BlendFunction.GLINT} and depth-tested for equality. 26.3 still draws
     * two glints that way, the patterned shield's and the trimmed armour's. Such a pass
     * has no surface of its own to carry the glint, and captured as a layer it would
     * stand in front of the surface it covers by less than the runtime's ray-resume
     * offset and hide that surface (see {@code RemixEntitySubmitter.glintFor}). So it is
     * not captured, and a patterned shield or trimmed armour shows no glint.
     */
    static boolean isGlintPass(RenderPipeline pipeline) {
        return BlendFunction.GLINT.equals(blendFunction(pipeline));
    }

    /**
     * How to read the layer's vertices, or empty when it draws primitives that
     * do not become triangles, such as lines.
     */
    static Optional<Topology> topology(RenderType layer) {
        return topology(layer.primitiveTopology());
    }

    static Optional<Topology> topology(PrimitiveTopology topology) {
        return switch (topology) {
            case QUADS -> Optional.of(Topology.QUADS);
            case TRIANGLE_STRIP -> Optional.of(Topology.TRIANGLE_STRIP);
            default -> Optional.empty();
        };
    }

    /**
     * Whether a layer that draws without a texture still carries geometry worth
     * submitting to Remix. In 26.3, as in 1.21.1, the lead between a mob and its
     * holder is the only such layer.
     *
     * <p>Two conditions, both about what the layer can actually shade with.
     * Vertex colour is the only surface detail such a layer has, so a layer
     * without it carries nothing to submit; that excludes the water mask, which
     * exists to write depth. And of the untextured layers that do have colour,
     * the triangle strips are world geometry while the quads are screen
     * furniture such as the nameplate background, which should not become
     * scene geometry that lights and casts shadows.
     */
    static boolean isCapturableUntexturedLayer(RenderType layer) {
        // Only layers with no texture at all qualify: a textured layer that
        // capturableTexture declined must not re-enter capture through here.
        return extractTexture(layer).isEmpty()
                && topology(layer).filter(t -> t == Topology.TRIANGLE_STRIP).isPresent()
                && layer.format().contains(COLOR_ATTRIBUTE);
    }

    /**
     * Whether the layer's vertex content differs every frame, so no mesh built from
     * it can be shared across frames and none should be pooled.
     *
     * <p>Vanilla streams two kinds of geometry through an entity collector whose
     * vertices never repeat: triangle strips, which it computes from live positions
     * (the lead between a mob and its holder), and the beacon beam, whose texture
     * coordinate scrolls with world time. Everything else it draws is rigid: a
     * model part or baked quad whose pose changes while its local vertices do not.
     */
    static boolean isStreamedPerFrame(RenderType layer) {
        return streamedPerFrameCache.computeIfAbsent(layer, RenderLayerUtils::isStreamedPerFrameUncached);
    }

    private static boolean isStreamedPerFrameUncached(RenderType layer) {
        return classifyStreamedPerFrame(topology(layer).orElse(Topology.QUADS),
                isBeaconBeamPipeline(layer.pipeline()));
    }

    static boolean classifyStreamedPerFrame(Topology topology, boolean beaconBeamProgram) {
        return topology == Topology.TRIANGLE_STRIP || beaconBeamProgram;
    }

    static boolean isBeaconBeamPipeline(RenderPipeline pipeline) {
        return pipeline == RenderPipelines.BEACON_BEAM_OPAQUE
                || pipeline == RenderPipelines.BEACON_BEAM_TRANSLUCENT;
    }

    /**
     * Converts Minecraft's pipeline state into the material behavior that Remix
     * needs. This deliberately inspects the pipeline and its blend function rather
     * than render-type names so mod-created layers using the standard pipelines
     * behave correctly.
     *
     * <p>The block-breaking crack multiplies the surface under it, a blend Remix
     * has no material for. Its texels are either fully opaque grey or fully
     * transparent, so alpha blending them over the surface is the closest surface
     * behaviour and it is classified as translucent.
     */
    static SurfaceMode surfaceMode(RenderType layer) {
        RenderPipeline pipeline = layer.pipeline();
        BlendFunction blend = blendFunction(pipeline);
        return classifySurfaceMode(
                isEmissivePipeline(pipeline),
                BlendFunction.ADDITIVE.equals(blend),
                BlendFunction.TRANSLUCENT.equals(blend) || pipeline == RenderPipelines.CRUMBLING,
                isSolidPipeline(pipeline),
                isTextPipeline(pipeline));
    }

    /** The first colour target's blend function, or null for an opaque pipeline. */
    private static BlendFunction blendFunction(RenderPipeline pipeline) {
        List<ColorTargetState> targets = pipeline.getColorTargetStates();
        for (ColorTargetState target : targets) {
            if (target != null) {
                return target.blendFunction().orElse(null);
            }
        }
        return null;
    }

    /** The eyes and the translucent-emissive entity pipelines, which vanilla draws unlit at full brightness. */
    static boolean isEmissivePipeline(RenderPipeline pipeline) {
        return pipeline == RenderPipelines.EYES
                || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE;
    }

    /**
     * The solid entity pipelines: the one every opaque model draws with, its forward
     * z-offset variant, and the glinted one, which draws the same solid surface with the
     * glint added in its shader; see {@link #glint}.
     */
    static boolean isSolidPipeline(RenderPipeline pipeline) {
        return pipeline == RenderPipelines.ENTITY_SOLID
                || pipeline == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD
                || pipeline == RenderPipelines.ENTITY_SOLID_GLINT;
    }

    /** The world-text pipelines, intensity (grayscale) pages included. See-through ones never get here. */
    static boolean isTextPipeline(RenderPipeline pipeline) {
        return pipeline == RenderPipelines.TEXT
                || pipeline == RenderPipelines.TEXT_GRAYSCALE
                || pipeline == RenderPipelines.TEXT_POLYGON_OFFSET
                || pipeline == RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET
                || pipeline == RenderPipelines.TEXT_SEE_THROUGH
                || pipeline == RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH;
    }

    /**
     * The text pipelines blend in vanilla only so glyph edges antialias; what they draw,
     * glyphs, map pixels and map icons, is coverage whose texels are either there or
     * not. Cutout keeps those quads out of the blended path, so text and maps are opaque
     * hits that cast shadows and appear in reflections instead of ghosted decals.
     */
    static SurfaceMode classifySurfaceMode(boolean emissiveProgram, boolean additiveTransparency,
                                           boolean translucentTransparency, boolean solidProgram,
                                           boolean textProgram) {
        if (emissiveProgram || additiveTransparency) {
            return SurfaceMode.EMISSIVE;
        }
        if (textProgram) {
            return SurfaceMode.CUTOUT;
        }
        if (translucentTransparency) {
            return SurfaceMode.TRANSLUCENT;
        }
        if (solidProgram) {
            return SurfaceMode.OPAQUE;
        }
        return SurfaceMode.CUTOUT;
    }
}
