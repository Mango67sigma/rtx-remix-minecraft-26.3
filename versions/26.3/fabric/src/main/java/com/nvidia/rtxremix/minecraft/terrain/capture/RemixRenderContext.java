/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.core.Direction;

/**
 * The Fabric {@link QuadEmitter} that block state models emit into during a section build.
 *
 * <p>Since Fabric API for 26.x every block model, vanilla or modded, reaches the terrain
 * through {@code FabricBlockStateModel.emitQuads}: Fabric's own section-compiler hook routes
 * {@code ModelBlockRenderer.tesselateBlock} that way, and the vanilla model classes carry
 * allocation-free Fabric overrides. This emitter is therefore the one sink for block quads.
 *
 * <p>The emitter is the active renderer's own, from {@link Renderer#quadEmitter}, not a
 * hand-written {@code QuadEmitter}. A model part baked as a Fabric mesh outputs it with
 * {@code MeshView.outputTo(emitter)}, and the renderer's mesh implementation may assume the
 * emitter is one of its own: Indigo's {@code MeshViewImpl.outputTo} casts it to
 * {@code MutableQuadViewImpl} and copies the encoded quads into its buffer. Only the
 * renderer's emitter survives that.
 *
 * <p>The consumer runs once per emitted quad, after every transform the model pushed (the
 * renderer applies them last-pushed first), so the cull test sees the quad's final cull face.
 * It is the test Indigo's {@code AltModelBlockRendererImpl} pushes as its bottom transform;
 * applying it in the consumer is equivalent and needs no push and pop per block. A culled
 * quad is dropped; the rest are staged by the sink from the {@link QuadView}. The emitter
 * clears itself after each quad. Nothing is allocated per quad.
 *
 * <p>Thread-local: one instance is reused per worker thread (via ThreadLocal in
 * {@link BlockQuadEmitter}).
 */
final class RemixRenderContext {

    /** Where emitted quads go: the block being captured. */
    interface QuadSink {
        /** True when the face is hidden by its neighbour; never true for the null face. */
        boolean isFaceCulled(Direction face);

        /** Stages one quad that survived its transforms and the cull test. */
        void stageEmittedQuad(QuadView quad);
    }

    private final QuadEmitter emitter;

    RemixRenderContext(QuadSink sink) {
        this(sink, Renderer.get());
    }

    RemixRenderContext(QuadSink sink, Renderer renderer) {
        this.emitter = renderer.quadEmitter((MutableQuadView quad) -> {
            Direction cull = quad.cullFace();
            if (cull != null && sink.isFaceCulled(cull)) {
                return;
            }
            sink.stageEmittedQuad(quad);
        });
    }

    /** Resets the emitter's quad data for the next block. */
    void prepare() {
        emitter.clear();
    }

    QuadEmitter emitter() {
        return emitter;
    }
}
