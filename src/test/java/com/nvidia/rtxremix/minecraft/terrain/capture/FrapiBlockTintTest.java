/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A block a mod draws through Fabric's renderer API keeps its tint. Since 26.x every block
 * model, vanilla or modded, emits into the renderer's own quad emitter, so the tint index a
 * quad carries reaches {@link BlockQuadEmitter#stageEmittedQuad} however the model built the
 * quad, and staging multiplies the block's tint into the quad's vertex color as the renderer
 * does. 26.x has no render material that could opt a quad out of its tint: a quad is tinted
 * exactly when its tint index is set.
 */
class FrapiBlockTintTest {

    @Test
    void anEmittedQuadKeepsItsTintIndex() {
        assertEquals(2, stageOne(emitter -> emitter.tintIndex(2)));
        assertEquals(-1, stageOne(emitter -> { }));
    }

    @Test
    void aCopiedQuadKeepsItsTintIndex() {
        QuadEmitter source = IndigoRenderer.INSTANCE.quadEmitter(quad -> { });
        source.tintIndex(4);
        assertEquals(4, stageOne(emitter -> emitter.copyFrom(source)));
        source.tintIndex(-1);
        assertEquals(-1, stageOne(emitter -> emitter.copyFrom(source)));
    }

    @Test
    void theTintMultipliesEachChannelAndKeepsTheVertexAlpha() {
        assertEquals(0xFF7FB238, BlockQuadEmitter.tintFrapiColor(0xFFFFFFFF, 0x7FB238));
        // 0x80 * 0x80 / 0xFF rounds down to 0x40, as ARGB.multiply does.
        assertEquals(0x40404040, BlockQuadEmitter.tintFrapiColor(0x40808080, 0x808080));
        assertEquals(0x80336699, BlockQuadEmitter.tintFrapiColor(0x80336699, 0xFFFFFF));
    }

    @Test
    void aTintIndexPastTheBlocksTintSourcesKeepsTheVertexColor() {
        // The block's tint is -1 for an index past the end of its tint sources.
        assertEquals(0x80336699, BlockQuadEmitter.tintFrapiColor(0x80336699, -1));
    }

    /** Emits one quad into a capture context and returns the tint index the sink saw. */
    private static int stageOne(Consumer<QuadEmitter> build) {
        List<Integer> tints = new ArrayList<>();
        RemixRenderContext context = new RemixRenderContext(new RemixRenderContext.QuadSink() {
            @Override
            public boolean isFaceCulled(Direction face) {
                return false;
            }

            @Override
            public void stageEmittedQuad(QuadView quad) {
                tints.add(quad.tintIndex());
            }
        }, IndigoRenderer.INSTANCE);
        QuadEmitter emitter = context.emitter();
        context.prepare();
        emitter.pos(0, 0, 0, 0).pos(1, 1, 0, 0).pos(2, 1, 1, 0).pos(3, 0, 1, 0);
        build.accept(emitter);
        emitter.emit();
        assertEquals(1, tints.size());
        return tints.get(0);
    }
}
