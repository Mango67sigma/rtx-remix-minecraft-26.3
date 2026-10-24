/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Fabric {@link QuadEmitter} that block state models emit into during a section build.
 *
 * <p>Since Fabric API for 26.x every block model, vanilla or modded, reaches the terrain
 * through {@code FabricBlockStateModel.emitQuads}: Fabric's own section-compiler hook routes
 * {@code ModelBlockRenderer.tesselateBlock} that way, and the vanilla model classes carry
 * allocation-free Fabric overrides. This emitter is therefore the one sink for block quads.
 * {@link CapturingQuadEmitter#emit()} runs the pushed transforms, culls on the quad's cull
 * face, and hands the quad to {@link BlockQuadEmitter#stageEmittedQuad}, which stages it into
 * the section's {@link DirectQuadWriter}. Nothing is allocated per quad.
 *
 * <p>Thread-local: one instance is reused per worker thread (via ThreadLocal in
 * {@link BlockQuadEmitter}).
 */
final class RemixRenderContext {

    private final CapturingQuadEmitter emitter;

    RemixRenderContext(BlockQuadEmitter sink) {
        this.emitter = new CapturingQuadEmitter(sink);
    }

    /** Resets the emitter for the next block. */
    void prepare() {
        emitter.transformStack.clear();
        emitter.clear();
    }

    QuadEmitter emitter() {
        return emitter;
    }

    /**
     * Complete {@link QuadEmitter} that stores quad data in flat arrays and stages the quad on
     * {@link #emit()}. Defaults after {@link #clear()} match Indigo's: white vertices, no cull
     * face, the {@code CUTOUT} layer, default ambient occlusion, no tint, no tag.
     */
    static final class CapturingQuadEmitter implements QuadEmitter {

        private final BlockQuadEmitter sink;

        private final float[] posData = new float[12];
        private final float[] uvData = new float[8];
        private final float[] normData = new float[12];
        private int normalFlags;
        private final int[] colors = new int[4];
        private final int[] lightmaps = new int[4];

        private Direction cullFaceVal;
        private Direction nominalFaceVal;
        private QuadAtlas atlasVal = QuadAtlas.BLOCK;
        private ChunkSectionLayer chunkLayerVal = ChunkSectionLayer.CUTOUT;
        private RenderType itemRenderTypeVal;
        private RenderType itemGlintRenderTypeVal;
        private RenderType itemGlintSpecialRenderTypeVal;
        private boolean emissiveVal;
        private Direction shadeDirectionOverrideVal;
        private TriState ambientOcclusionVal = TriState.DEFAULT;
        private ItemStackRenderState.FoilType foilTypeVal;
        private ShadeMode shadeModeVal = ShadeMode.ENHANCED;
        private boolean animatedVal;
        private int tintIndexVal = -1;
        private int tagVal;

        /**
         * The sprite the quad was baked with, recorded by {@link #fromBakedQuad} and
         * {@link #materialBake}; a quad copied from a pre-built mesh carries none and is
         * resolved through the atlas sprite finder when staged.
         */
        private TextureAtlasSprite bakedSprite;

        private final Vector3f faceNormalCache = new Vector3f();
        private boolean faceNormalDirty = true;

        /** Transforms in push order; applied last-pushed first, as Indigo does. */
        final List<QuadTransform> transformStack = new ArrayList<>();

        private final Vector3f scratchNormal = new Vector3f();
        private final Vector4f scratchPos = new Vector4f();

        CapturingQuadEmitter(BlockQuadEmitter sink) {
            this.sink = sink;
            clear();
        }

        TextureAtlasSprite bakedSprite() {
            return bakedSprite;
        }

        @Override
        public QuadEmitter clear() {
            for (int i = 0; i < 4; i++) {
                colors[i] = -1; // 0xFFFFFFFF
                lightmaps[i] = 0;
            }
            Arrays.fill(posData, 0);
            Arrays.fill(uvData, 0);
            Arrays.fill(normData, 0);
            normalFlags = 0;
            cullFaceVal = null;
            nominalFaceVal = null;
            atlasVal = QuadAtlas.BLOCK;
            chunkLayerVal = ChunkSectionLayer.CUTOUT;
            itemRenderTypeVal = null;
            itemGlintRenderTypeVal = null;
            itemGlintSpecialRenderTypeVal = null;
            emissiveVal = false;
            shadeDirectionOverrideVal = null;
            ambientOcclusionVal = TriState.DEFAULT;
            foilTypeVal = null;
            shadeModeVal = ShadeMode.ENHANCED;
            animatedVal = false;
            tintIndexVal = -1;
            tagVal = 0;
            bakedSprite = null;
            faceNormalDirty = true;
            return this;
        }

        @Override
        public QuadEmitter emit() {
            // Last pushed runs first, as in Indigo; a transform returning false drops the quad.
            for (int i = transformStack.size() - 1; i >= 0; i--) {
                if (!transformStack.get(i).transform(this)) {
                    clear();
                    return this;
                }
            }

            Direction cull = cullFaceVal;
            if (cull != null && sink.isFaceCulled(cull)) {
                clear();
                return this;
            }

            sink.stageEmittedQuad(this);
            clear();
            return this;
        }

        @Override
        public void pushTransform(QuadTransform transform) {
            if (transform == null) {
                throw new NullPointerException("QuadTransform cannot be null!");
            }
            transformStack.add(transform);
        }

        @Override
        public void popTransform() {
            transformStack.remove(transformStack.size() - 1);
        }

        // --- Setters (return QuadEmitter per interface contract) ---

        @Override
        public QuadEmitter pos(int vertexIndex, float x, float y, float z) {
            int i = vertexIndex * 3;
            posData[i] = x; posData[i + 1] = y; posData[i + 2] = z;
            faceNormalDirty = true;
            return this;
        }

        @Override
        public QuadEmitter color(int vertexIndex, int color) {
            colors[vertexIndex] = color;
            return this;
        }

        @Override
        public QuadEmitter uv(int vertexIndex, float u, float v) {
            int i = vertexIndex * 2;
            uvData[i] = u; uvData[i + 1] = v;
            return this;
        }

        @Override
        public QuadEmitter lightmap(int vertexIndex, int lightmap) {
            lightmaps[vertexIndex] = lightmap;
            return this;
        }

        @Override
        public QuadEmitter normal(int vertexIndex, float x, float y, float z) {
            int i = vertexIndex * 3;
            normData[i] = x; normData[i + 1] = y; normData[i + 2] = z;
            normalFlags |= 1 << vertexIndex;
            return this;
        }

        @Override
        public QuadEmitter nominalFace(@Nullable Direction face) {
            this.nominalFaceVal = face;
            return this;
        }

        @Override
        public QuadEmitter cullFace(@Nullable Direction face) {
            // Indigo makes the cull face the nominal face as well.
            this.cullFaceVal = face;
            this.nominalFaceVal = face;
            return this;
        }

        @Override
        public QuadEmitter atlas(QuadAtlas quadAtlas) {
            this.atlasVal = quadAtlas;
            return this;
        }

        @Override
        public QuadEmitter chunkLayer(ChunkSectionLayer layer) {
            this.chunkLayerVal = layer;
            return this;
        }

        @Override
        public QuadEmitter itemRenderType(RenderType renderType) {
            this.itemRenderTypeVal = renderType;
            return this;
        }

        @Override
        public QuadEmitter itemGlintRenderType(RenderType renderType) {
            this.itemGlintRenderTypeVal = renderType;
            return this;
        }

        @Override
        public QuadEmitter itemGlintSpecialRenderType(RenderType renderType) {
            this.itemGlintSpecialRenderTypeVal = renderType;
            return this;
        }

        @Override
        public QuadEmitter emissive(boolean emissive) {
            this.emissiveVal = emissive;
            return this;
        }

        @Override
        public QuadEmitter shadeDirectionOverride(@Nullable Direction shadeDirection) {
            this.shadeDirectionOverrideVal = shadeDirection;
            return this;
        }

        @Override
        public QuadEmitter ambientOcclusion(TriState ao) {
            if (ao == null) {
                throw new NullPointerException("ambient occlusion TriState may not be null");
            }
            this.ambientOcclusionVal = ao;
            return this;
        }

        @Override
        public QuadEmitter foilType(ItemStackRenderState.@Nullable FoilType foilType) {
            this.foilTypeVal = foilType;
            return this;
        }

        @Override
        public QuadEmitter shadeMode(ShadeMode mode) {
            if (mode == null) {
                throw new NullPointerException("ShadeMode may not be null");
            }
            this.shadeModeVal = mode;
            return this;
        }

        @Override
        public QuadEmitter animated(boolean animated) {
            this.animatedVal = animated;
            return this;
        }

        @Override
        public QuadEmitter tintIndex(int tintIndex) {
            this.tintIndexVal = tintIndex;
            return this;
        }

        @Override
        public QuadEmitter tag(int tag) {
            this.tagVal = tag;
            return this;
        }

        /**
         * Bakes the sprite as the interface default does and remembers which sprite it was,
         * so the staged quad resolves its material without a sprite-finder lookup.
         */
        @Override
        public QuadEmitter materialBake(Material.Baked material, int bakeFlags) {
            QuadEmitter.super.materialBake(material, bakeFlags);
            this.bakedSprite = material.sprite();
            return this;
        }

        @Override
        public QuadEmitter fromBakedQuad(BakedQuad quad) {
            BakedQuad.MaterialInfo materialInfo = quad.materialInfo();
            Vector3fc p0 = quad.position0();
            Vector3fc p1 = quad.position1();
            Vector3fc p2 = quad.position2();
            Vector3fc p3 = quad.position3();
            pos(0, p0.x(), p0.y(), p0.z());
            pos(1, p1.x(), p1.y(), p1.z());
            pos(2, p2.x(), p2.y(), p2.z());
            pos(3, p3.x(), p3.y(), p3.z());
            colors[0] = colors[1] = colors[2] = colors[3] = -1;
            long packedUV0 = quad.packedUV0();
            long packedUV1 = quad.packedUV1();
            long packedUV2 = quad.packedUV2();
            long packedUV3 = quad.packedUV3();
            uv(0, UVPair.unpackU(packedUV0), UVPair.unpackV(packedUV0));
            uv(1, UVPair.unpackU(packedUV1), UVPair.unpackV(packedUV1));
            uv(2, UVPair.unpackU(packedUV2), UVPair.unpackV(packedUV2));
            uv(3, UVPair.unpackU(packedUV3), UVPair.unpackV(packedUV3));
            int lightEmission = materialInfo.lightEmission();
            int lightmap = LightCoordsUtil.pack(lightEmission, lightEmission);
            lightmaps[0] = lightmaps[1] = lightmaps[2] = lightmaps[3] = lightmap;
            normalFlags = 0;
            nominalFaceVal = quad.direction();
            TextureAtlasSprite sprite = materialInfo.sprite();
            QuadAtlas atlas = QuadAtlas.ofLocation(sprite.atlasLocation());
            atlasVal = atlas == null ? QuadAtlas.BLOCK : atlas;
            animatedVal = sprite.contents().isAnimated();
            chunkLayerVal = materialInfo.layer();
            itemRenderTypeVal = materialInfo.itemRenderType();
            itemGlintRenderTypeVal = materialInfo.itemGlintRenderType();
            itemGlintSpecialRenderTypeVal = materialInfo.itemGlintSpecialRenderType();
            tintIndexVal = materialInfo.tintIndex();
            shadeDirectionOverrideVal = materialInfo.shadeDirectionOverride();
            emissiveVal = lightEmission == 15;
            bakedSprite = sprite;
            return this;
        }

        @Override
        public QuadEmitter copyFrom(QuadView source) {
            if (source instanceof CapturingQuadEmitter other) {
                System.arraycopy(other.posData, 0, posData, 0, 12);
                System.arraycopy(other.uvData, 0, uvData, 0, 8);
                System.arraycopy(other.normData, 0, normData, 0, 12);
                System.arraycopy(other.colors, 0, colors, 0, 4);
                System.arraycopy(other.lightmaps, 0, lightmaps, 0, 4);
                normalFlags = other.normalFlags;
                bakedSprite = other.bakedSprite;
            } else {
                for (int v = 0; v < 4; v++) {
                    pos(v, source.x(v), source.y(v), source.z(v));
                    colors[v] = source.color(v);
                    uv(v, source.u(v), source.v(v));
                    lightmaps[v] = source.lightmap(v);
                    if (source.hasNormal(v)) {
                        normal(v, source.normalX(v), source.normalY(v), source.normalZ(v));
                    }
                }
                bakedSprite = null;
            }
            faceNormalDirty = true;
            cullFaceVal = source.cullFace();
            nominalFaceVal = source.nominalFace();
            atlasVal = source.atlas();
            chunkLayerVal = source.chunkLayer();
            itemRenderTypeVal = source.itemRenderType();
            itemGlintRenderTypeVal = source.itemGlintRenderType();
            itemGlintSpecialRenderTypeVal = source.itemGlintSpecialRenderType();
            emissiveVal = source.emissive();
            shadeDirectionOverrideVal = source.shadeDirectionOverride();
            ambientOcclusionVal = source.ambientOcclusion();
            foilTypeVal = source.foilType();
            shadeModeVal = source.shadeMode();
            animatedVal = source.animated();
            tintIndexVal = source.tintIndex();
            tagVal = source.tag();
            return this;
        }

        // --- QuadView getters ---

        @Override public float x(int vertexIndex) { return posData[vertexIndex * 3]; }
        @Override public float y(int vertexIndex) { return posData[vertexIndex * 3 + 1]; }
        @Override public float z(int vertexIndex) { return posData[vertexIndex * 3 + 2]; }

        @Override
        public float posByIndex(int vertexIndex, int coordinateIndex) {
            return posData[vertexIndex * 3 + coordinateIndex];
        }

        @Override
        public Vector3f copyPos(int vertexIndex, @Nullable Vector3f target) {
            if (target == null) target = new Vector3f();
            int i = vertexIndex * 3;
            target.set(posData[i], posData[i + 1], posData[i + 2]);
            return target;
        }

        @Override public int color(int vertexIndex) { return colors[vertexIndex]; }

        @Override public float u(int vertexIndex) { return uvData[vertexIndex * 2]; }
        @Override public float v(int vertexIndex) { return uvData[vertexIndex * 2 + 1]; }

        @Override
        public Vector2f copyUv(int vertexIndex, @Nullable Vector2f target) {
            if (target == null) target = new Vector2f();
            target.set(uvData[vertexIndex * 2], uvData[vertexIndex * 2 + 1]);
            return target;
        }

        @Override public int lightmap(int vertexIndex) { return lightmaps[vertexIndex]; }

        @Override
        public boolean hasNormal(int vertexIndex) {
            return (normalFlags & (1 << vertexIndex)) != 0;
        }

        @Override
        public float normalX(int vertexIndex) {
            return hasNormal(vertexIndex) ? normData[vertexIndex * 3] : Float.NaN;
        }

        @Override
        public float normalY(int vertexIndex) {
            return hasNormal(vertexIndex) ? normData[vertexIndex * 3 + 1] : Float.NaN;
        }

        @Override
        public float normalZ(int vertexIndex) {
            return hasNormal(vertexIndex) ? normData[vertexIndex * 3 + 2] : Float.NaN;
        }

        @Override
        public @Nullable Vector3f copyNormal(int vertexIndex, @Nullable Vector3f target) {
            if (!hasNormal(vertexIndex)) return null;
            if (target == null) target = new Vector3f();
            int i = vertexIndex * 3;
            target.set(normData[i], normData[i + 1], normData[i + 2]);
            return target;
        }

        /**
         * The geometric face normal, from the first three vertices as the terrain capture has
         * always computed it. A degenerate quad keeps its unnormalised, near-zero cross product
         * rather than turning into NaN.
         */
        @Override
        public Vector3fc faceNormal() {
            if (faceNormalDirty) {
                float x0 = posData[0], y0 = posData[1], z0 = posData[2];
                float e1x = posData[3] - x0, e1y = posData[4] - y0, e1z = posData[5] - z0;
                float e2x = posData[6] - x0, e2y = posData[7] - y0, e2z = posData[8] - z0;
                float nx = e1y * e2z - e1z * e2y;
                float ny = e1z * e2x - e1x * e2z;
                float nz = e1x * e2y - e1y * e2x;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 0.001f) {
                    nx /= len; ny /= len; nz /= len;
                }
                faceNormalCache.set(nx, ny, nz);
                faceNormalDirty = false;
            }
            return faceNormalCache;
        }

        @Override
        public Direction lightFace() {
            Vector3fc n = faceNormal();
            return Direction.getApproximateNearest(n.x(), n.y(), n.z());
        }

        @Override public @Nullable Direction nominalFace() { return nominalFaceVal; }
        @Override public @Nullable Direction cullFace() { return cullFaceVal; }
        @Override public QuadAtlas atlas() { return atlasVal; }
        @Override public ChunkSectionLayer chunkLayer() { return chunkLayerVal; }

        @Override
        public RenderType itemRenderType() {
            return itemRenderTypeVal != null ? itemRenderTypeVal : Sheets.cutoutBlockItemSheet();
        }

        @Override
        public RenderType itemGlintRenderType() {
            return itemGlintRenderTypeVal != null ? itemGlintRenderTypeVal : Sheets.cutoutBlockItemGlintSheet();
        }

        @Override
        public RenderType itemGlintSpecialRenderType() {
            return itemGlintSpecialRenderTypeVal != null
                    ? itemGlintSpecialRenderTypeVal
                    : Sheets.cutoutBlockItemGlintSpecialSheet();
        }

        @Override public boolean emissive() { return emissiveVal; }
        @Override public @Nullable Direction shadeDirectionOverride() { return shadeDirectionOverrideVal; }
        @Override public TriState ambientOcclusion() { return ambientOcclusionVal; }
        @Override public ItemStackRenderState.@Nullable FoilType foilType() { return foilTypeVal; }
        @Override public ShadeMode shadeMode() { return shadeModeVal; }
        @Override public boolean animated() { return animatedVal; }
        @Override public int tintIndex() { return tintIndexVal; }
        @Override public int tag() { return tagVal; }

        @Override
        public void buffer(int overlayCoords, VertexConsumer vertexConsumer) {
            Vector3fc fn = faceNormal();
            for (int i = 0; i < 4; i++) {
                float nx, ny, nz;
                if (hasNormal(i)) {
                    nx = normData[i * 3]; ny = normData[i * 3 + 1]; nz = normData[i * 3 + 2];
                } else {
                    nx = fn.x(); ny = fn.y(); nz = fn.z();
                }
                vertexConsumer.addVertex(x(i), y(i), z(i), colors[i], u(i), v(i),
                        overlayCoords, lightmaps[i], nx, ny, nz);
            }
        }

        @Override
        public void buffer(int overlayCoords, PoseStack.Pose pose, VertexConsumer vertexConsumer) {
            Matrix4f posMatrix = pose.pose();
            Vector3f normal = scratchNormal;
            Vector4f position = scratchPos;
            for (int i = 0; i < 4; i++) {
                position.set(x(i), y(i), z(i), 1.0f);
                position.mul(posMatrix);
                if (hasNormal(i)) {
                    pose.transformNormal(normData[i * 3], normData[i * 3 + 1], normData[i * 3 + 2], normal);
                } else {
                    pose.transformNormal(faceNormal(), normal);
                }
                vertexConsumer.addVertex(position.x(), position.y(), position.z(), colors[i], u(i), v(i),
                        overlayCoords, lightmaps[i], normal.x(), normal.y(), normal.z());
            }
        }
    }
}
