/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * The {@link SubmitNodeCollector} every Remix capture pass hands to vanilla's
 * renderers.
 *
 * <p>Since 26.2 a renderer does not write vertices. It submits models, items, text,
 * leashes and custom geometry to a collector, and the feature renderers write them
 * later, batched by {@link RenderType} across every entity of the frame. That batch
 * has lost what the capture is built on, the owner each part belongs to, so the
 * capture does not read the feature renderers' buffers. It stands in for the
 * collector instead and executes every submit on the spot, the way the matching
 * feature renderer would execute it at prepare time, into the
 * {@link RemixVertexConsumerProvider} of the pass that owns the capture: a model is
 * animated and compiled once, here, and never reaches a feature renderer, so the
 * invariant that a model is evaluated once per frame holds. The pose stack is live
 * rather than copied, because nothing is deferred.
 *
 * <p>What is not reproduced is deliberate. Shadows are dropped, because Remix traces
 * the real ones. Outline copies, the water mask, shape outlines, the crumbling
 * overlay, particles and gizmos have no surface Remix could paint and are dropped
 * too; the terrain crack is captured by {@code RemixBlockDamagePass} at its own hook.
 * Ordering is ignored: the capture keys parts by owner and submit order, not by phase.
 *
 * <p>A glinted surface is captured once, under the glinted render type vanilla draws it
 * with, as a layer of its own beside the plain one. Vanilla draws the glint in the same
 * pipeline as the surface, from a second sampler, and the capture leaves it there: the
 * submitter gives that layer the surface's material with the glint in its emission (see
 * {@link RenderLayerUtils#glint}). Vanilla's two separate glint passes, the patterned
 * shield's and the trimmed armour's, are not captured.
 */
public final class RemixSubmitNodeCollector implements SubmitNodeCollector {

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float NAME_TAG_SCALE = 0.025F;
    private static final int NAME_TAG_BACKGROUND = -16777216;

    private final RemixVertexConsumerProvider provider;
    private final QuadInstance quadInstance = new QuadInstance();
    private final PoseStack.Pose scratchPose = new PoseStack.Pose();
    private final Matrix4f scratchMatrix = new Matrix4f();
    private final GlyphRenderer glyphRenderer = new GlyphRenderer();
    private final MovingBlockQuadOutput movingBlockOutput = new MovingBlockQuadOutput();
    private ModelBlockRenderer movingBlockRenderer;
    private boolean movingBlockAmbientOcclusion;

    public RemixSubmitNodeCollector(RemixVertexConsumerProvider provider) {
        this.provider = provider;
    }

    /** Order decides vanilla's draw batching; the capture keys by owner and submit order, so every order is this. */
    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    // ---- Dropped submits ----

    /** Remix traces the real shadow; vanilla's blob would double it. */
    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    /** A block entity's crack is not captured, as it was not when the capture replaced vanilla's provider. */
    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                           int lightCoords, int overlayCoords, int tintedColor,
                                           ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
    }

    /** The terrain crack is captured where {@code LevelRenderer} submits it, by {@code RemixBlockDamagePass}. */
    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
                                         boolean isBlockTranslucent) {
    }

    /** Lines have no triangles; the targeted-block outline is {@code RemixBlockOutlinePass}'s ribbon. */
    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    /** Particles belong to the particle pipeline, which streams its own meshes. */
    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }

    /** A modded feature renderer's node has no vertex path the capture could run. */
    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }

    // ---- Models ----

    /**
     * Animates and compiles the model at once, as {@code ModelFeatureRenderer} would at
     * prepare time. A sprite-mapped model is compiled through vanilla's own
     * {@link UvMapping#wrap} expander, the one wrapper {@code CuboidMixin} looks through,
     * so the sprite travels with each cuboid. An outline render type is vanilla's glow
     * copy of the model and carries no surface, so it is dropped; the main submit of a
     * glowing entity arrives separately under its real render type.
     */
    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor,
                                @Nullable UvMapping uvMapping, int outlineColor) {
        if (renderType.isOutline()) {
            return;
        }
        VertexConsumer buffer = provider.getBuffer(renderType);
        if (!(buffer instanceof RemixCapturingConsumer)) {
            return;
        }
        model.setupAnim(state);
        model.renderToBuffer(poseStack, mapped(buffer, uvMapping), lightCoords, overlayCoords, tintedColor);
    }

    /**
     * Compiles one part straight from the stack, without the {@code Model.Simple} the
     * interface default would allocate per call.
     */
    @Override
    public void submitModelPart(ModelPart modelPart, PoseStack poseStack, RenderType renderType, int lightCoords,
                                int overlayCoords, @Nullable UvMapping uvMapping, int tintedColor, int outlineColor) {
        if (renderType.isOutline()) {
            return;
        }
        VertexConsumer buffer = provider.getBuffer(renderType);
        if (!(buffer instanceof RemixCapturingConsumer)) {
            return;
        }
        modelPart.render(poseStack, mapped(buffer, uvMapping), lightCoords, overlayCoords, tintedColor);
    }

    private static VertexConsumer mapped(VertexConsumer buffer, @Nullable UvMapping uvMapping) {
        return uvMapping != null ? uvMapping.wrap(buffer) : buffer;
    }

    // ---- Items and block models ----

    /**
     * Captures an item's quads in model order, each under the render type
     * {@code ItemFeatureRenderer} draws it with, then adds the lit element a hand-held
     * item carries. The pose stack still holds the item's block-space pose, so the held
     * flame finds the torch tip where it was.
     *
     * <p>A foil quad is captured under its glinted render type, whose layer the submitter
     * gives the glint. A special foil (a compass or clock) is captured like a standard
     * one: vanilla's decal coordinate for it is dropped, see
     * {@link RemixCapturingConsumer#putBakedQuadWithGlint}.
     */
    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords,
                           int outlineColor, int[] tintLayers, ItemQuads quads, ItemStackRenderState.FoilType foilType) {
        PoseStack.Pose pose = poseStack.last();
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        for (BakedQuad quad : quads.all()) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            VertexConsumer buffer = provider.getBuffer(itemRenderType(material, foilType));
            if (!(buffer instanceof RemixCapturingConsumer)) {
                continue;
            }
            quadInstance.setColor(layerColor(tintLayers, material));
            buffer.putBakedQuad(pose, quad, quadInstance);
        }
        HeldItemEffectsRenderer.render(EntityRenderContext.get(), displayContext, poseStack, provider);
    }

    /** The render type {@code ItemFeatureRenderer} draws a quad with under {@code foilType}. */
    private static RenderType itemRenderType(BakedQuad.MaterialInfo material,
                                             ItemStackRenderState.FoilType foilType) {
        return switch (foilType) {
            case NONE -> material.itemRenderType();
            case STANDARD -> material.itemGlintRenderType();
            case SPECIAL -> material.itemGlintSpecialRenderType();
        };
    }

    private static int layerColor(int[] tintLayers, BakedQuad.MaterialInfo material) {
        if (!material.isTinted()) {
            return -1;
        }
        int layer = material.tintIndex();
        return layer >= 0 && layer < tintLayers.length ? tintLayers[layer] : -1;
    }

    /** A block shown by an entity (a minecart's cargo, an enderman's block): its parts' quads as item-like geometry. */
    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        if (renderType.isOutline()) {
            return;
        }
        VertexConsumer buffer = provider.getBuffer(renderType);
        if (!(buffer instanceof RemixCapturingConsumer)) {
            return;
        }
        PoseStack.Pose pose = poseStack.last();
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                putPartQuads(buffer, pose, part.getQuads(direction), tintLayers);
            }
            putPartQuads(buffer, pose, part.getQuads(null), tintLayers);
        }
    }

    private void putPartQuads(VertexConsumer buffer, PoseStack.Pose pose, List<BakedQuad> quads, int[] tintLayers) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            boolean useTintLayer = tintIndex != -1 && tintIndex < tintLayers.length;
            quadInstance.setColor(useTintLayer ? tintLayers[tintIndex] : -1);
            buffer.putBakedQuad(pose, quad, quadInstance);
        }
    }

    /**
     * A falling or piston-moved block, tessellated with vanilla's block model renderer
     * as {@code MovingBlockFeatureRenderer} would, lighting and all, into the moving
     * block render types. The per-quad offset vanilla applies on its own stack is
     * applied here on the live one.
     */
    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockState blockState = movingBlockRenderState.blockState;
        BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(blockState);
        boolean ambientOcclusion = minecraft.gameRenderer.gameRenderState().optionsRenderState.ambientOcclusion;
        boolean cutoutLeaves = minecraft.gameRenderer.gameRenderState().optionsRenderState.cutoutLeaves;
        if (movingBlockRenderer == null || movingBlockAmbientOcclusion != ambientOcclusion) {
            movingBlockRenderer = new ModelBlockRenderer(ambientOcclusion, false, minecraft.getBlockColors());
            movingBlockAmbientOcclusion = ambientOcclusion;
        }
        movingBlockOutput.begin(poseStack,
                model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT),
                ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState));
        try {
            long seed = blockState.getSeed(movingBlockRenderState.randomSeedPos);
            movingBlockRenderer.tesselateBlock(movingBlockOutput, 0.0F, 0.0F, 0.0F, movingBlockRenderState,
                    movingBlockRenderState.blockPos, blockState, model, seed);
        } finally {
            movingBlockOutput.end();
        }
    }

    /** The quad sink of {@link #submitMovingBlock}, reused across submits. */
    private final class MovingBlockQuadOutput implements BlockQuadOutput {
        private PoseStack poseStack;
        private boolean forceTranslucent;
        private boolean forceOpaque;

        void begin(PoseStack poseStack, boolean forceTranslucent, boolean forceOpaque) {
            this.poseStack = poseStack;
            this.forceTranslucent = forceTranslucent;
            this.forceOpaque = forceOpaque;
        }

        void end() {
            this.poseStack = null;
        }

        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            RenderType renderType;
            if (forceTranslucent) {
                renderType = RenderTypes.translucentMovingBlock();
            } else if (forceOpaque) {
                renderType = RenderTypes.solidMovingBlock();
            } else {
                renderType = switch (quad.materialInfo().layer()) {
                    case SOLID -> RenderTypes.solidMovingBlock();
                    case CUTOUT -> RenderTypes.cutoutMovingBlock();
                    case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
                };
            }
            VertexConsumer buffer = provider.getBuffer(renderType);
            if (!(buffer instanceof RemixCapturingConsumer)) {
                return;
            }
            poseStack.pushPose();
            poseStack.translate(x, y, z);
            buffer.putBakedQuad(poseStack.last(), quad, instance);
            poseStack.popPose();
        }
    }

    // ---- Custom geometry ----

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        if (renderType.isOutline()) {
            return;
        }
        VertexConsumer buffer = provider.getBuffer(renderType);
        if (!(buffer instanceof RemixCapturingConsumer)) {
            return;
        }
        customGeometryRenderer.render(poseStack.last(), buffer);
    }

    // ---- Flame and leash ----

    /**
     * The burning-entity flame, built as {@code FlameFeatureRenderer} builds it, on a
     * scratch copy of the pose because the feature renderer scales and turns the pose it
     * was given.
     */
    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        VertexConsumer buffer = provider.getBuffer(RenderTypes.entityCutoutCull(TextureAtlas.LOCATION_BLOCKS));
        if (!(buffer instanceof RemixCapturingConsumer)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlasSprite fire1 = minecraft.getAtlasManager().get(ModelBakery.FIRE_0);
        TextureAtlasSprite fire2 = minecraft.getAtlasManager().get(ModelBakery.FIRE_1);
        PoseStack.Pose pose = scratchPose;
        pose.set(poseStack.last());
        float s = renderState.boundingBoxWidth * 1.4F;
        pose.scale(s, s, s);
        float r = 0.5F;
        float h = renderState.boundingBoxHeight / s;
        float yo = 0.0F;
        pose.rotate(rotation);
        pose.translate(0.0F, 0.0F, 0.3F - (int) h * 0.02F);
        float zo = 0.0F;
        int ss = 0;
        int lightCoords = LightCoordsUtil.withBlock(renderState.lightCoords, 15);

        while (h > 0.0F) {
            TextureAtlasSprite tex = ss % 2 == 0 ? fire1 : fire2;
            float u0 = tex.getU0();
            float v0 = tex.getV0();
            float u1 = tex.getU1();
            float v1 = tex.getV1();
            if (ss / 2 % 2 == 0) {
                float tmp = u1;
                u1 = u0;
                u0 = tmp;
            }

            fireVertex(pose, buffer, -r, -yo, zo, u1, v1, lightCoords);
            fireVertex(pose, buffer, r, -yo, zo, u0, v1, lightCoords);
            fireVertex(pose, buffer, r, 1.4F - yo, zo, u0, v0, lightCoords);
            fireVertex(pose, buffer, -r, 1.4F - yo, zo, u1, v0, lightCoords);
            h -= 0.45F;
            yo -= 0.45F;
            r *= 0.9F;
            zo -= 0.03F;
            ss++;
        }
    }

    private static void fireVertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z,
                                   float u, float v, int lightCoords) {
        buffer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setUv1(0, 10).setLight(lightCoords)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /** The lead between a mob and its holder, a triangle strip built as {@code LeashFeatureRenderer} builds it. */
    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        VertexConsumer buffer = provider.getBuffer(RenderTypes.leash());
        if (!(buffer instanceof RemixCapturingConsumer)) {
            return;
        }
        Matrix4f pose = scratchMatrix.set(poseStack.last().pose());
        float dx = (float) (leashState.end.x - leashState.start.x);
        float dy = (float) (leashState.end.y - leashState.start.y);
        float dz = (float) (leashState.end.z - leashState.start.z);
        float offsetFactor = Mth.invSqrt(dx * dx + dz * dz) * 0.05F / 2.0F;
        float dxOff = dz * offsetFactor;
        float dzOff = dx * offsetFactor;
        pose.translate((float) leashState.offset.x, (float) leashState.offset.y, (float) leashState.offset.z);

        for (int k = 0; k <= 24; k++) {
            addLeashVertexPair(buffer, pose, dx, dy, dz, 0.05F, dxOff, dzOff, k, false, leashState);
        }

        for (int k = 24; k >= 0; k--) {
            addLeashVertexPair(buffer, pose, dx, dy, dz, 0.0F, dxOff, dzOff, k, true, leashState);
        }
    }

    private static void addLeashVertexPair(VertexConsumer buffer, Matrix4fc pose, float dx, float dy, float dz,
                                           float fudge, float dxOff, float dzOff, int k, boolean backwards,
                                           EntityRenderState.LeashState state) {
        float progress = k / 24.0F;
        int block = (int) Mth.lerp(progress, state.startBlockLight, state.endBlockLight);
        int sky = (int) Mth.lerp(progress, state.startSkyLight, state.endSkyLight);
        int lightCoords = LightCoordsUtil.pack(block, sky);
        float colorModifier = k % 2 == (backwards ? 1 : 0) ? 0.7F : 1.0F;
        float r = 0.5F * colorModifier;
        float g = 0.4F * colorModifier;
        float b = 0.3F * colorModifier;
        float x = dx * progress;
        float y;
        if (state.slack) {
            y = dy > 0.0F ? dy * progress * progress : dy - dy * (1.0F - progress) * (1.0F - progress);
        } else {
            y = dy * progress;
        }

        float z = dz * progress;
        buffer.addVertex(pose, x - dxOff, y + fudge, z + dzOff).setColor(r, g, b, 1.0F).setLight(lightCoords);
        buffer.addVertex(pose, x + dxOff, y + 0.05F - fudge, z - dzOff).setColor(r, g, b, 1.0F).setLight(lightCoords);
    }

    // ---- Text ----

    /**
     * A name tag, posed as {@code SubmitNodeCollection.submitNameTag} poses it. Only the
     * depth-tested copy is captured: the see-through copy is drawn over the whole scene
     * in vanilla, which no placement in a traced scene reproduces, and it would only
     * double the text beside it. The depth-tested copy of a see-through tag is the
     * plain white text without a background, as in vanilla.
     */
    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
        if (nameTagAttachment == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        poseStack.pushPose();
        poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z);
        poseStack.rotate(camera.orientation);
        poseStack.scale(NAME_TAG_SCALE, -NAME_TAG_SCALE, NAME_TAG_SCALE);
        float x = -minecraft.font.width(name) / 2.0F;
        FormattedCharSequence visualOrderName = name.getVisualOrderText();
        if (seeThrough) {
            renderText(minecraft.font, poseStack.last().pose(), Font.DisplayMode.NORMAL,
                    LightCoordsUtil.lightCoordsWithEmission(lightCoords, 2),
                    x, offset, visualOrderName, false, -1, 0, 0);
        } else {
            float backgroundAlpha = minecraft.gameRenderer.gameRenderState().optionsRenderState.getBackgroundOpacity(0.25F);
            int backgroundColor = ARGB.color(backgroundAlpha, NAME_TAG_BACKGROUND);
            float textAlpha = Math.max((backgroundAlpha + 0.75F) * 0.5F, 0.5F);
            int textColor = ARGB.color(textAlpha, -1);
            renderText(minecraft.font, poseStack.last().pose(), Font.DisplayMode.NORMAL, lightCoords,
                    x, offset, visualOrderName, false, textColor, backgroundColor, 0);
        }
        poseStack.popPose();
    }

    /** Sign, hanging-sign and other world text, prepared as {@code TextFeatureRenderer} prepares it. */
    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor,
                           int outlineColor) {
        if (displayMode == Font.DisplayMode.SEE_THROUGH) {
            return;
        }
        renderText(Minecraft.getInstance().font, poseStack.last().pose(), displayMode, lightCoords,
                x, y, string, dropShadow, color, backgroundColor, outlineColor);
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color,
                                     Font.DisplayMode displayMode, int lightCoords) {
        if (displayMode == Font.DisplayMode.SEE_THROUGH) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        glyphRenderer.begin(poseStack.last().pose(), displayMode, lightCoords);
        glyphRenderer.acceptRenderable(font.prepareBackground(x0, y0, x1, y1, color));
        glyphRenderer.end();
    }

    private void renderText(Font font, Matrix4fc pose, Font.DisplayMode displayMode, int lightCoords,
                            float x, float y, FormattedCharSequence string, boolean dropShadow,
                            int color, int backgroundColor, int outlineColor) {
        glyphRenderer.begin(pose, displayMode, lightCoords);
        try {
            if (outlineColor == 0) {
                font.prepareText(string, x, y, color, dropShadow, false, backgroundColor).visit(glyphRenderer);
            } else {
                // Glowing text: eight outline copies on the normal layer, the ink on the
                // polygon-offset layer above them, as vanilla orders them.
                Font.PreparedText outline = font.prepare8xTextOutline(string, x, y, outlineColor);
                Font.PreparedText text = font.prepareText(string, x, y, color, false, false, 0);
                glyphRenderer.displayMode = Font.DisplayMode.NORMAL;
                outline.visit(glyphRenderer);
                glyphRenderer.displayMode = Font.DisplayMode.POLYGON_OFFSET;
                text.visit(glyphRenderer);
            }
        } finally {
            glyphRenderer.end();
        }
    }

    /** Writes each glyph quad into the consumer of its page's render type; one instance, reused. */
    private final class GlyphRenderer implements Font.GlyphVisitor {
        private Matrix4fc pose;
        private int lightCoords;
        private Font.DisplayMode displayMode = Font.DisplayMode.NORMAL;

        void begin(Matrix4fc pose, Font.DisplayMode displayMode, int lightCoords) {
            this.pose = pose;
            this.displayMode = displayMode;
            this.lightCoords = lightCoords;
        }

        void end() {
            this.pose = null;
        }

        @Override
        public void acceptRenderable(TextRenderable renderable) {
            VertexConsumer buffer = provider.getBuffer(renderable.renderType(displayMode));
            if (!(buffer instanceof RemixCapturingConsumer)) {
                return;
            }
            renderable.render(pose, buffer, lightCoords, false);
        }
    }
}
