/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fallback star field is vanilla's: {@code SkyRenderer.buildStars} transcribed line for
 * line below, built through vanilla's own {@code BufferBuilder} and read back through the
 * capture the mixin uses, must equal {@link VanillaStarField#generate()} float for float.
 */
class VanillaStarFieldTest {

    @AfterEach
    void forgetCapture() {
        VanillaStarField.clearCaptureForTest();
    }

    @Test
    void theGeneratedFieldIsTheFieldVanillaBuilds() {
        float[] generated = VanillaStarField.generate();

        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(
                DefaultVertexFormat.POSITION.getVertexSize() * 1500 * 4)) {
            BufferBuilder bufferBuilder = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.QUADS,
                    DefaultVertexFormat.POSITION);
            buildStarsAsVanillaDoes(bufferBuilder);
            try (MeshData mesh = bufferBuilder.buildOrThrow()) {
                VanillaStarField.capture(mesh);
            }
        }
        assertTrue(VanillaStarField.hasCapturedPositions());
        float[] captured = VanillaStarField.copyPositions();

        assertEquals(0, generated.length % 12, "four XYZ vertices per star");
        // A uniform point in the cube lands in the unit ball about pi/6 of the time.
        int stars = generated.length / 12;
        assertTrue(stars > 700 && stars < 870, "stars: " + stars);
        assertArrayEquals(captured, generated);
    }

    @Test
    void withoutACaptureTheSkyStillGetsTheStars() {
        assertFalse(VanillaStarField.hasCapturedPositions());
        float[] first = VanillaStarField.copyPositions();
        float[] second = VanillaStarField.copyPositions();

        assertArrayEquals(VanillaStarField.generate(), first);
        assertArrayEquals(first, second);
        assertNotSame(first, second, "every caller gets its own copy");
        for (int i = 0; i < first.length; i += 3) {
            float distance = (float) Math.sqrt(Mth.lengthSquared(first[i], first[i + 1], first[i + 2]));
            assertEquals(100.0f, distance, 0.3f, "star vertices sit on the 100-block shell");
        }
    }

    /** {@code SkyRenderer.buildStars} in 26.3, minus the GPU upload. */
    private static void buildStarsAsVanillaDoes(BufferBuilder bufferBuilder) {
        RandomSource random = RandomSource.createThreadLocalInstance(10842L);
        for (int i = 0; i < 1500; i++) {
            float x = random.nextFloat() * 2.0F - 1.0F;
            float y = random.nextFloat() * 2.0F - 1.0F;
            float z = random.nextFloat() * 2.0F - 1.0F;
            float starSize = 0.15F + random.nextFloat() * 0.1F;
            float lengthSq = Mth.lengthSquared(x, y, z);
            if (!(lengthSq <= 0.010000001F) && !(lengthSq >= 1.0F)) {
                Vector3f starCenter = new Vector3f(x, y, z).normalize(100.0F);
                float zRot = (float) (random.nextDouble() * (float) Math.PI * 2.0);
                Matrix3f rotation = new Matrix3f().rotateTowards(new Vector3f(starCenter).negate(),
                        new Vector3f(0.0F, 1.0F, 0.0F)).rotateZ(-zRot);
                bufferBuilder.addVertex(new Vector3f(starSize, -starSize, 0.0F).mul(rotation).add(starCenter));
                bufferBuilder.addVertex(new Vector3f(starSize, starSize, 0.0F).mul(rotation).add(starCenter));
                bufferBuilder.addVertex(new Vector3f(-starSize, starSize, 0.0F).mul(rotation).add(starCenter));
                bufferBuilder.addVertex(new Vector3f(-starSize, -starSize, 0.0F).mul(rotation).add(starCenter));
            }
        }
    }
}
