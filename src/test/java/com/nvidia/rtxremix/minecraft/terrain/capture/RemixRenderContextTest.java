/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The terrain capture's quad sink runs on the renderer's own emitter, which is what a model
 * part baked as a Fabric mesh needs: Indigo's {@code MeshViewImpl.outputTo} casts its target
 * to Indigo's {@code MutableQuadViewImpl}, so a hand-written {@code QuadEmitter} crashed the
 * section build on the first such block.
 */
class RemixRenderContextTest {

    /** Records what reaches the sink; culls the faces in {@link #culled}. */
    private static final class RecordingSink implements RemixRenderContext.QuadSink {
        final Set<Direction> culled = EnumSet.noneOf(Direction.class);
        final List<Direction> cullQueries = new ArrayList<>();
        final List<float[]> staged = new ArrayList<>();
        final List<Integer> stagedTints = new ArrayList<>();

        @Override
        public boolean isFaceCulled(Direction face) {
            cullQueries.add(face);
            return culled.contains(face);
        }

        @Override
        public void stageEmittedQuad(QuadView quad) {
            float[] positions = new float[12];
            for (int v = 0; v < 4; v++) {
                positions[v * 3] = quad.x(v);
                positions[v * 3 + 1] = quad.y(v);
                positions[v * 3 + 2] = quad.z(v);
            }
            staged.add(positions);
            stagedTints.add(quad.tintIndex());
        }
    }

    private static void unitQuad(QuadEmitter emitter, float y, Direction cullFace, int tintIndex) {
        emitter.pos(0, 0, y, 0).pos(1, 0, y, 1).pos(2, 1, y, 1).pos(3, 1, y, 0);
        emitter.uv(0, 0, 0).uv(1, 0, 1).uv(2, 1, 1).uv(3, 1, 0);
        emitter.cullFace(cullFace);
        emitter.tintIndex(tintIndex);
        emitter.emit();
    }

    private static Mesh threeQuadMesh() {
        MutableMesh builder = IndigoRenderer.INSTANCE.mutableMesh();
        QuadEmitter emitter = builder.emitter();
        unitQuad(emitter, 0.25f, null, -1);
        unitQuad(emitter, 1.0f, Direction.UP, 0);
        unitQuad(emitter, 0.0f, Direction.DOWN, 1);
        return builder.immutableCopy();
    }

    @Test
    void aPrebuiltMeshOutputsIntoTheContextAndStagesEveryUnculledQuad() {
        RecordingSink sink = new RecordingSink();
        sink.culled.add(Direction.UP);
        RemixRenderContext context = new RemixRenderContext(sink, IndigoRenderer.INSTANCE);
        Mesh mesh = threeQuadMesh();

        context.prepare();
        assertDoesNotThrow(() -> mesh.outputTo(context.emitter()));

        assertEquals(List.of(Direction.UP, Direction.DOWN), sink.cullQueries,
                "the null face is never tested; each cull face once");
        assertEquals(2, sink.staged.size());
        assertEquals(0.25f, sink.staged.get(0)[1]);
        assertEquals(0.0f, sink.staged.get(1)[1]);
        assertEquals(List.of(-1, 1), sink.stagedTints);
    }

    @Test
    void theCullTestSeesTheCullFaceAfterTheModelsTransforms() {
        RecordingSink sink = new RecordingSink();
        sink.culled.add(Direction.NORTH);
        RemixRenderContext context = new RemixRenderContext(sink, IndigoRenderer.INSTANCE);
        Mesh mesh = threeQuadMesh();

        // A model moving every quad onto a hidden face, as a connected-texture model may.
        QuadEmitter emitter = context.emitter();
        emitter.pushTransform(quad -> {
            quad.cullFace(Direction.NORTH);
            return true;
        });
        mesh.outputTo(emitter);
        emitter.popTransform();
        assertEquals(0, sink.staged.size());

        // A transform that rejects a quad drops it before the sink sees it.
        emitter.pushTransform(quad -> quad.tintIndex() != 0);
        mesh.outputTo(emitter);
        emitter.popTransform();
        assertEquals(List.of(-1, 1), sink.stagedTints);
    }

    @Test
    void directEmissionClearsBetweenQuads() {
        RecordingSink sink = new RecordingSink();
        RemixRenderContext context = new RemixRenderContext(sink, IndigoRenderer.INSTANCE);
        QuadEmitter emitter = context.emitter();

        context.prepare();
        unitQuad(emitter, 0.5f, Direction.EAST, 3);
        emitter.pos(0, 0, 0, 0).pos(1, 0, 0, 1).pos(2, 1, 0, 1).pos(3, 1, 0, 0);
        emitter.emit();

        assertEquals(List.of(Direction.EAST), sink.cullQueries,
                "the second quad starts from the cleared defaults: no cull face");
        assertEquals(List.of(3, -1), sink.stagedTints);
        assertFalse(sink.culled.contains(Direction.EAST));
    }
}
