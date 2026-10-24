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

/**
 * Holds the positions of the star field vanilla's canonical builder generated, so the Remix sky
 * draws exactly the stars Minecraft would.
 *
 * <p>Vanilla builds its star buffer once per {@code SkyRenderer}, which {@code LevelRenderer}
 * creates lazily on its first sky pass and recreates on a resource reload; the
 * {@code SkyRendererMixin} publishes each build here. The builder runs on the render thread,
 * as does every reader.
 */
public final class VanillaStarField {

    private static float[] positions;

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
     * Returns a copy of the captured position-only star vertices, packed XYZ in Minecraft's
     * star-field object space, or null until vanilla has built its star buffer.
     */
    public static float[] copyPositions() {
        float[] captured = positions;
        return captured == null ? null : captured.clone();
    }
}
