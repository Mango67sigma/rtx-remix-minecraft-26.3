/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconPose;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** Renders the independently rotating yoke and lamp head above the static mount. */
public final class CopperBeaconBlockEntityRenderer
        implements BlockEntityRenderer<CopperBeaconBlockEntity, CopperBeaconRenderState> {

    public static final ModelLayerLocation BODY_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "body");
    public static final ModelLayerLocation LENS_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "lens");
    private static final SpriteId COPPER_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            Identifier.withDefaultNamespace("block/copper_block"));
    static final Identifier LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens");
    static final Identifier UNLIT_LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens_unlit");
    private static final SpriteId LENS_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            LENS_SPRITE_ID);
    private static final SpriteId UNLIT_LENS_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            UNLIT_LENS_SPRITE_ID);

    private final YokeModel yokeModel;
    private final LensModel lensModel;
    private final SpriteGetter sprites;
    private final CopperBeaconPose.Pose pose = new CopperBeaconPose.Pose();
    private final CopperStripMapping copperMapping = new CopperStripMapping();

    public CopperBeaconBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        yokeModel = new YokeModel(context.bakeLayer(BODY_MODEL_LAYER).getChild("yoke"));
        lensModel = new LensModel(context.bakeLayer(LENS_MODEL_LAYER).getChild("lens"));
        sprites = context.sprites();
    }

    public static LayerDefinition getBodyTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        PartDefinition yokeData = root.addOrReplaceChild(
                "yoke",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(0.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-5.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f)
                        .texOffs(0, 0).addBox(3.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        yokeData.addOrReplaceChild(
                "head",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-6.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(4.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f),
                PartPose.ZERO);
        return LayerDefinition.create(modelData, 16, 16);
    }

    public static LayerDefinition getLensTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        root.addOrReplaceChild(
                "lens",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -3.0f, -4.01f, 6.0f, 6.0f, 0.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        return LayerDefinition.create(modelData, 6, 6);
    }

    @Override
    public CopperBeaconRenderState createRenderState() {
        return new CopperBeaconRenderState();
    }

    /**
     * Reads the frame's pose from the block entity: the mount and facing from its
     * block state, the head angles at the presentation time
     * {@link CopperBeaconAnimationClock} reconstructs from the synchronized world
     * tick, the articulation they evaluate to, and the lens state the lamp is drawn
     * with.
     */
    @Override
    public void extractRenderState(
            CopperBeaconBlockEntity beacon,
            CopperBeaconRenderState state,
            float partialTicks,
            Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderState.extractBase(beacon, state, breakProgress);
        BlockState blockState = beacon.getBlockState();
        state.up = blockState.getValue(CopperBeaconBlock.MOUNT);
        state.forward = blockState.getValue(BlockStateProperties.FACING);
        double animationTick = CopperBeaconAnimationClock.sample(beacon.getLevel(), partialTicks);
        state.yawDegrees = beacon.yawAngleAt(animationTick);
        state.pitchDegrees = beacon.pitchAngleAt(animationTick);
        extractArticulation(state.up, state.forward, state.yawDegrees, state.pitchDegrees,
                pose, state.articulation);
        state.active = beacon.isActive();
        state.intensityMultiplier = beacon.getIntensityMultiplier();
        state.lensTint = beacon.getLensTint();
    }

    /**
     * Submits the yoke and the lens as models over this beacon's articulation. Vanilla's
     * collector compiles them at feature prepare, once every block entity of the frame
     * has been submitted, so the articulation travels with the render state: each
     * model's {@code setupAnim} poses the shared parts from the articulation it is
     * handed (see {@link CopperBeaconArticulation}). The Remix
     * capture collector runs the same {@code setupAnim} and {@code renderToBuffer} on
     * the spot, which renders exactly the parts {@code submitModelPart} rendered.
     */
    @Override
    public void submit(
            CopperBeaconRenderState state,
            PoseStack matrices,
            SubmitNodeCollector collector,
            CameraRenderState camera) {
        matrices.pushPose();
        matrices.translate(0.5, 0.5, 0.5);
        matrices.rotate(state.articulation.mountingRotation);
        matrices.translate(-0.5, -0.5, -0.5);

        // The yoke keeps the copper sprite's vertical strip, a UV mapping of its own
        // rather than the bare sprite, so it is captured face by face as it was. The
        // sprite is the same for every beacon, so one mapping serves all of them.
        copperMapping.sprite = sprites.get(COPPER_TEXTURE);
        collector.submitModel(yokeModel, state.articulation, matrices, RenderTypes.entitySolid(TextureAtlas.LOCATION_BLOCKS),
                state.lightCoords, OverlayTexture.NO_OVERLAY, -1, copperMapping, 0);
        // The lens is painted with its sprite directly: the capture keys the cuboid
        // by that sprite and gives the lit one the beacon's emissive material.
        TextureAtlasSprite lensSprite = sprites.get(lensTexture(state.active, state.intensityMultiplier));
        collector.submitModel(lensModel, state.articulation, matrices, RenderTypes.entitySolid(TextureAtlas.LOCATION_BLOCKS),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.lensTint, lensSprite, 0);
        matrices.popPose();
    }

    /** Evaluates a placement and its angles into a beacon's mounting rotation and model angles. */
    static void extractArticulation(Direction up, Direction forward, float yawDegrees, float pitchDegrees,
                                    CopperBeaconPose.Pose scratch, CopperBeaconArticulation destination) {
        CopperBeaconPose.evaluate(up, forward, yawDegrees, pitchDegrees, scratch);
        destination.mountingRotation.set(scratch.mountingRotation());
        destination.modelYawRadians = scratch.modelYawRadians();
        destination.modelPitchRadians = scratch.modelPitchRadians();
    }

    static Quaternionf mountingRotation(Direction upDirection, Direction forwardDirection) {
        return CopperBeaconPose.mountingRotation(
                upDirection, forwardDirection, new Quaternionf());
    }

    /** The fork turns with the yaw only; the head inside it pitches. */
    static void applyYokeArticulation(ModelPart yoke, ModelPart head, float modelYawRadians,
                                      float modelPitchRadians) {
        yoke.yRot = modelYawRadians;
        yoke.xRot = 0.0f;
        head.yRot = 0.0f;
        head.xRot = modelPitchRadians;
    }

    /** The lens is a root of its own and takes both angles, so it stays seated in the head. */
    static void applyLensArticulation(ModelPart lens, float modelYawRadians, float modelPitchRadians) {
        lens.yRot = modelYawRadians;
        lens.xRot = modelPitchRadians;
    }

    /** The yoke and its child head, posed from a beacon's articulation. */
    static final class YokeModel extends Model<CopperBeaconArticulation> {
        private final ModelPart head;

        YokeModel(ModelPart yoke) {
            super(yoke, RenderTypes::entitySolid);
            head = yoke.getChild("head");
        }

        ModelPart head() {
            return head;
        }

        @Override
        public void setupAnim(CopperBeaconArticulation state) {
            super.setupAnim(state);
            applyYokeArticulation(root, head, state.modelYawRadians, state.modelPitchRadians);
        }
    }

    /** The lens, posed from a beacon's articulation. */
    static final class LensModel extends Model<CopperBeaconArticulation> {
        LensModel(ModelPart lens) {
            super(lens, RenderTypes::entitySolid);
        }

        @Override
        public void setupAnim(CopperBeaconArticulation state) {
            super.setupAnim(state);
            applyLensArticulation(root, state.modelYawRadians, state.modelPitchRadians);
        }
    }

    static SpriteId lensTexture(boolean active, float intensityMultiplier) {
        return active && intensityMultiplier > 0.0f
                ? LENS_TEXTURE
                : UNLIT_LENS_TEXTURE;
    }

    static float copperTextureV(float v) {
        return 0.25f + v * 0.5f;
    }

    /**
     * Maps the yoke's cube UVs into the copper sprite's rectangle through a vertical
     * strip of it: keeps resource-pack details along the texture edges while excluding
     * all four corners, where stylized copper textures commonly place rivets.
     */
    private static final class CopperStripMapping implements UvMapping {
        TextureAtlasSprite sprite;

        @Override
        public float getU(float offset) {
            return sprite.getU(offset);
        }

        @Override
        public float getV(float offset) {
            return sprite.getV(copperTextureV(offset));
        }
    }
}
