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
    /** The vertex attribute a layer must carry to be shaded by vertex colour alone. */
    private static final String COLOR_ATTRIBUTE = "Color";

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

    /**
     * Returns the texture a captured layer is submitted under, or empty when Remix has
     * nothing to draw for the layer. Results are cached by layer identity.
     *
     * <p>Empty covers three cases. Untextured layers (shadow, debug, lines) carry no
     * material identity at all. Layers that pass the depth test unconditionally, the
     * see-through text of a name tag, are drawn over the whole scene in vanilla; no
     * placement in a ray-traced scene reproduces that, and vanilla draws the
     * depth-tested copy of the same text beside them, so their quads would only double
     * it. And the end portal and gateway, whose two textures feed a screen-space effect
     * rather than a surface, have no texture Remix could paint them with.
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
        if (layer.isOutline() || !depthTested(pipeline) || isScreenSpaceEffect(pipeline)) {
            return Optional.empty();
        }
        return extractTexture(layer);
    }

    /**
     * The texture bound to the layer's primary sampler. Glint layers bind the glint
     * texture to a second sampler, which is left out: the glint is a screen-space
     * shimmer with no place on a traced surface, and the item underneath keeps its own.
     */
    private static Optional<Identifier> extractTexture(RenderType layer) {
        RenderSetup.TextureBinding binding = setup(layer).textures.get(PRIMARY_SAMPLER);
        return binding == null ? Optional.empty() : Optional.of(binding.location());
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
     * z-offset variant, and the glinted one, whose item used to reach the solid layer
     * underneath the glint pass.
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
