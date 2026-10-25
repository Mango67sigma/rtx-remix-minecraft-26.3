/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * Holds the positions of the star field vanilla's canonical builder generated, so the Remix sky
 * draws exactly the stars Minecraft would.
 *
 * <p>Vanilla builds its star buffer once per {@code SkyRenderer}, which {@code LevelRenderer}
 * creates lazily in its sky pass and recreates on a resource reload; the
 * {@code SkyRendererMixin} publishes each build here. The sky pass is skipped while the
 * camera's fog hides the sky (blindness, powder snow, lava, boss fog), so a world joined in
 * one of those never builds vanilla's stars. The Remix sky then takes {@link #generate()},
 * the same deterministic field transcribed from {@code SkyRenderer.buildStars}: the capture
 * stays the preferred source, and the two are the same stars. The builder runs on the render
 * thread, as does every reader.
 */
public final class VanillaStarField {

    /** {@code SkyRenderer.buildStars}'s seed. */
    static final long SEED = 10842L;
    /** How many candidate stars vanilla draws; those outside the shell are dropped. */
    static final int ATTEMPTS = 1500;
    private static final float STAR_DISTANCE = 100.0F;

    private static float[] positions;
    private static float[] generated;

    private VanillaStarField() {
    }

    /**
     * Copies the position element of every vertex of a freshly built star mesh.
     *
     * @param mesh the mesh vanilla's {@code buildStars} produced; it is only read, and stays
     *     owned by the caller
     */
    public static void capture(MeshData mesh) {
        MeshData.DrawState state = mesh.drawState();
        VertexFormat format = state.format();
        VertexFormatElement position = format.getElement(DefaultVertexFormat.POSITION_SEMANTIC_NAME);
        if (position == null) {
            positions = null;
            return;
        }

        int vertexCount = state.vertexCount();
        int stride = format.getVertexSize();
        int positionOffset = position.offset();
        ByteBuffer vertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        float[] captured = new float[vertexCount * 3];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int source = vertex * stride + positionOffset;
            int destination = vertex * 3;
            captured[destination] = vertices.getFloat(source);
            captured[destination + 1] = vertices.getFloat(source + Float.BYTES);
            captured[destination + 2] = vertices.getFloat(source + 2 * Float.BYTES);
        }
        positions = captured;
    }

    /**
     * Returns a copy of the star vertices, packed XYZ in Minecraft's star-field object space,
     * four per star in vanilla's quad order: the ones vanilla built when it has built them,
     * otherwise the same field generated here. Never null.
     */
    public static float[] copyPositions() {
        float[] captured = positions;
        if (captured != null) {
            return captured.clone();
        }
        float[] fallback = generated;
        if (fallback == null) {
            fallback = generate();
            generated = fallback;
        }
        return fallback.clone();
    }

    /** Whether the positions come from vanilla's own build rather than {@link #generate()}. */
    public static boolean hasCapturedPositions() {
        return positions != null;
    }

    /**
     * {@code SkyRenderer.buildStars} transcribed: the same random source, seed, draw order,
     * rejection test and JOML operations, so every float matches the vertex vanilla writes.
     * Runs once; the allocation per star is vanilla's own.
     */
    static float[] generate() {
        RandomSource random = RandomSource.createThreadLocalInstance(SEED);
        float[] out = new float[ATTEMPTS * 4 * 3];
        int written = 0;
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
        for (int i = 0; i < ATTEMPTS; i++) {
            float x = random.nextFloat() * 2.0F - 1.0F;
            float y = random.nextFloat() * 2.0F - 1.0F;
            float z = random.nextFloat() * 2.0F - 1.0F;
            float starSize = 0.15F + random.nextFloat() * 0.1F;
            float lengthSq = Mth.lengthSquared(x, y, z);
            if (!(lengthSq <= 0.010000001F) && !(lengthSq >= 1.0F)) {
                Vector3f starCenter = new Vector3f(x, y, z).normalize(STAR_DISTANCE);
                float zRot = (float) (random.nextDouble() * (float) Math.PI * 2.0);
                Matrix3f rotation = new Matrix3f()
                        .rotateTowards(new Vector3f(starCenter).negate(), up)
                        .rotateZ(-zRot);
                written = put(out, written, new Vector3f(starSize, -starSize, 0.0F).mul(rotation).add(starCenter));
                written = put(out, written, new Vector3f(starSize, starSize, 0.0F).mul(rotation).add(starCenter));
                written = put(out, written, new Vector3f(-starSize, starSize, 0.0F).mul(rotation).add(starCenter));
                written = put(out, written, new Vector3f(-starSize, -starSize, 0.0F).mul(rotation).add(starCenter));
            }
        }
        return written == out.length ? out : Arrays.copyOf(out, written);
    }

    private static int put(float[] out, int at, Vector3f vertex) {
        out[at] = vertex.x;
        out[at + 1] = vertex.y;
        out[at + 2] = vertex.z;
        return at + 3;
    }

    /** Forgets the captured field; for tests. */
    static void clearCaptureForTest() {
        positions = null;
    }
}
