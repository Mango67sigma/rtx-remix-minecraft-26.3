/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the glint emission a glinted surface's material carries: vanilla's glint transform
 * evaluated at time zero over the surface texture's coordinates, the texel it finds
 * squared, at a resolution that loses neither the surface's pixels nor the glint's.
 */
class GlintEmissionTest {

    private static final Identifier TRIDENT = Identifier.withDefaultNamespace("textures/entity/trident.png");

    @Test
    void aBakeIsNoCoarserThanItsSurfaceOrItsGlintAndNoLargerThanTheCap() {
        // The item glint lays eight copies of the 128-texel glint over the coordinates.
        assertEquals(1024, GlintEmission.size(1024, 8.0f, 128));
        assertEquals(1024, GlintEmission.size(512, 8.0f, 128));
        assertEquals(GlintEmission.MAX_SIZE, GlintEmission.size(4096, 8.0f, 128));
        // Armour: the 64x32 texture is finer than the 20 glint texels it shows.
        assertEquals(64, GlintEmission.size(64, 0.16f, 128));
        assertEquals(32, GlintEmission.size(32, 0.16f, 128));
        assertEquals(21, GlintEmission.size(1, 0.16f, 128));
        // A trident's 32 texels would halve the 64 glint texels across it.
        assertEquals(64, GlintEmission.size(32, 0.5f, 128));
    }

    /** Vanilla's {@code TextureTransform.setupGlintTexturing} at time zero, built the way it builds it. */
    private static Vector3f vanillaGlintCoordinate(float u, float v, float scale) {
        Matrix4f matrix = new Matrix4f().translation(0.0f, 0.0f, 0.0f);
        matrix.rotateZ((float) (Math.PI / 18)).scale(scale);
        return matrix.transformPosition(new Vector3f(u, v, 0.0f));
    }

    @Test
    void theGlintCoordinateIsVanillasTransformAtTimeZero() {
        float[] out = new float[2];
        for (RenderLayerUtils.Glint glint : RenderLayerUtils.Glint.values()) {
            for (float[] uv : new float[][] {{0f, 0f}, {1f, 0f}, {0f, 1f}, {0.3f, 0.7f}, {0.9f, 0.05f}}) {
                GlintEmission.glintCoordinate(uv[0], uv[1], glint.scale, out);
                Vector3f expected = vanillaGlintCoordinate(uv[0], uv[1], glint.scale);
                assertEquals(expected.x, out[0], 1e-5f, glint + " u at " + uv[0] + "," + uv[1]);
                assertEquals(expected.y, out[1], 1e-5f, glint + " v at " + uv[0] + "," + uv[1]);
            }
        }
    }

    @Test
    void aUniformGlintBakesToItsSquareEverywhere() {
        byte[] glint = new byte[4 * 4 * 4];
        for (int i = 0; i < glint.length; i += 4) {
            glint[i] = (byte) 200;
            glint[i + 1] = (byte) 100;
            glint[i + 2] = (byte) 50;
            glint[i + 3] = (byte) 255;
        }
        byte[] baked = GlintEmission.bake(glint, 4, 4, 8.0f, 8, 8);
        assertEquals(8 * 8 * 4, baked.length);
        for (int i = 0; i < baked.length; i += 4) {
            assertEquals(157, baked[i] & 0xFF);
            assertEquals(39, baked[i + 1] & 0xFF);
            assertEquals(10, baked[i + 2] & 0xFF);
            assertEquals(255, baked[i + 3] & 0xFF);
        }
    }

    /**
     * Over a glint whose red grows with x and green with y, bilinear sampling returns the
     * continuous texel position exactly, so each baked texel shows where vanilla's transform
     * took its surface coordinate.
     */
    @Test
    void eachTexelSquaresTheGlintWhereVanillasTransformTakesItsCoordinate() {
        int size = 128;
        byte[] glint = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = (y * size + x) * 4;
                glint[i] = (byte) (2 * x);
                glint[i + 1] = (byte) (2 * y);
                glint[i + 3] = (byte) 255;
            }
        }
        int width = 16;
        int height = 16;
        float scale = RenderLayerUtils.Glint.ENTITY.scale;
        byte[] baked = GlintEmission.bake(glint, size, size, scale, width, height);
        int checked = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Vector3f at = vanillaGlintCoordinate((x + 0.5f) / width, (y + 0.5f) / height, scale);
                float fx = at.x * size - 0.5f;
                float fy = at.y * size - 0.5f;
                // Away from the wrap, where the gradient's linear interpolation is exact.
                if (fx < 0f || fy < 0f || fx > size - 1 || fy > size - 1) continue;
                int i = (y * width + x) * 4;
                assertEquals(Math.round(4f * fx * fx / 255f), baked[i] & 0xFF, 1, "red at " + x + "," + y);
                assertEquals(Math.round(4f * fy * fy / 255f), baked[i + 1] & 0xFF, 1, "green at " + x + "," + y);
                checked++;
            }
        }
        assertTrue(checked > 100, "most texels map inside the glint without wrapping: " + checked);
    }

    @Test
    void theGlintRepeatsAndIsSampledBetweenTexelCentres() {
        // Two texels, red 0 and 200: their centres sit at u = 0.25 and 0.75.
        byte[] two = {0, 0, 0, (byte) 255, (byte) 200, 0, 0, (byte) 255};
        float[] texel = new float[3];
        GlintEmission.sample(two, 2, 1, 0.25f, 0.5f, texel);
        assertEquals(0f, texel[0], 1e-3f);
        GlintEmission.sample(two, 2, 1, 0.5f, 0.5f, texel);
        assertEquals(100f, texel[0], 1e-3f);
        GlintEmission.sample(two, 2, 1, 0.75f, 0.5f, texel);
        assertEquals(200f, texel[0], 1e-3f);
        // Past the right edge the texture wraps back to its first texel.
        GlintEmission.sample(two, 2, 1, 1.0f, 0.5f, texel);
        assertEquals(100f, texel[0], 1e-3f);
        float[] shifted = new float[3];
        GlintEmission.sample(two, 2, 1, 0.6f, 0.5f, texel);
        GlintEmission.sample(two, 2, 1, 3.6f, -1.5f, shifted);
        assertEquals(texel[0], shifted[0], 1e-3f);
    }

    /** Vanilla's own item glint, baked as an item atlas bakes it: a purple sheen that leaves dark gaps. */
    @Test
    void vanillasItemGlintBakesToAPurpleSheenWithDarkGaps() throws IOException {
        Identifier id = ItemFeatureRenderer.ENCHANTED_GLINT_ITEM;
        BufferedImage image = read(id);
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int i = (y * w + x) * 4;
                rgba[i] = (byte) (argb >> 16);
                rgba[i + 1] = (byte) (argb >> 8);
                rgba[i + 2] = (byte) argb;
                rgba[i + 3] = (byte) (argb >>> 24);
            }
        }
        int size = 256;
        byte[] baked = GlintEmission.bake(rgba, w, h, RenderLayerUtils.Glint.ITEM.scale, size, size);
        long red = 0, green = 0, blue = 0;
        int darkest = 255, brightest = 0;
        for (int i = 0; i < baked.length; i += 4) {
            int r = baked[i] & 0xFF, g = baked[i + 1] & 0xFF, b = baked[i + 2] & 0xFF;
            red += r;
            green += g;
            blue += b;
            int max = Math.max(r, Math.max(g, b));
            darkest = Math.min(darkest, max);
            brightest = Math.max(brightest, max);
        }
        assertTrue(blue > red && red > green, "purple: " + red + "/" + green + "/" + blue);
        assertTrue(blue / (double) (size * size) < 128, "faint on average");
        assertTrue(darkest <= 16, "dark gaps leave the item as it is: " + darkest);
        assertTrue(brightest >= 250, "bright streaks: " + brightest);
    }

    /** With the switch off, a glinted layer takes its plain counterpart's material. */
    @Test
    void theGlintSwitchDecidesWhetherAGlintedLayerCarriesItsGlint() {
        assertSame(RenderLayerUtils.Glint.ITEM,
                RemixEntitySubmitter.glintFor(RenderTypes.itemCutoutGlint(TextureAtlas.LOCATION_ITEMS), true));
        assertSame(RenderLayerUtils.Glint.ENTITY, RemixEntitySubmitter.glintFor(RenderTypes.entitySolidGlint(TRIDENT), true));
        assertNull(RemixEntitySubmitter.glintFor(RenderTypes.itemCutoutGlint(TextureAtlas.LOCATION_ITEMS), false));
        assertNull(RemixEntitySubmitter.glintFor(RenderTypes.itemCutout(TextureAtlas.LOCATION_ITEMS), true));
    }

    @Test
    void theGlintIntensityKnobAcceptsOnlyFiniteNonNegativeNumbers() {
        float fallback = RemixEntitySubmitter.GLINT_EMISSIVE_INTENSITY;
        assertTrue(fallback > 0f && fallback < RemixEntitySubmitter.EMISSIVE_INTENSITY, "dimmer than eyes");
        assertEquals(fallback, RemixEntitySubmitter.glintEmissiveIntensity(null));
        assertEquals(fallback, RemixEntitySubmitter.glintEmissiveIntensity(""));
        assertEquals(5f, RemixEntitySubmitter.glintEmissiveIntensity("5"));
        assertEquals(12.5f, RemixEntitySubmitter.glintEmissiveIntensity(" 12.5 "));
        assertEquals(0f, RemixEntitySubmitter.glintEmissiveIntensity("0"));
        assertEquals(fallback, RemixEntitySubmitter.glintEmissiveIntensity("-1"));
        assertEquals(fallback, RemixEntitySubmitter.glintEmissiveIntensity("NaN"));
        assertEquals(fallback, RemixEntitySubmitter.glintEmissiveIntensity("Infinity"));
        assertEquals(fallback, RemixEntitySubmitter.glintEmissiveIntensity("bright"));
    }

    private static BufferedImage read(Identifier id) throws IOException {
        String path = "/assets/" + id.getNamespace() + "/" + id.getPath();
        try (InputStream in = ItemFeatureRenderer.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " on the test classpath");
            return ImageIO.read(in);
        }
    }
}
