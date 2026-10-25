package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the glint texture's brightness-derived alpha and the glint material's launch knobs.
 *
 * <p>Vanilla's glint textures are opaque everywhere, and the runtime composites a blended
 * layer coplanar with its surface by its alpha, so an unmodified glint covers an enchanted
 * item with its dark purple. The GLINT material's albedo therefore carries
 * {@code alpha = max(r, g, b)} and keeps its colour.
 */
class RemixEntitySubmitterGlintAlphaTest {

    @Test
    void alphaBecomesTheBrightestChannelAndColourIsKept() {
        byte[] rgba = texels(
                0, 0, 0, 255,          // black: fully transparent
                255, 255, 255, 255,    // white: fully opaque
                88, 42, 143, 255,      // the item glint's mean texel
                200, 10, 10, 255,      // red-dominant
                10, 180, 20, 255);     // green-dominant
        RemixEntitySubmitter.deriveGlintAlpha(rgba);
        assertArrayEquals(texels(
                0, 0, 0, 0,
                255, 255, 255, 255,
                88, 42, 143, 143,
                200, 10, 10, 200,
                10, 180, 20, 180), rgba);
    }

    @Test
    void anExistingAlphaScalesTheDerivedOne() {
        byte[] rgba = texels(
                255, 0, 0, 128,
                128, 128, 128, 0,
                100, 50, 25, 51);
        RemixEntitySubmitter.deriveGlintAlpha(rgba);
        assertArrayEquals(texels(
                255, 0, 0, 128,
                128, 128, 128, 0,
                100, 50, 25, 20), rgba);
    }

    /** Vanilla's own glint textures, decoded as the submitter sees them, stop being opaque. */
    @Test
    void vanillaGlintTexturesBecomeMostlyTransparentWithTheirColoursIntact() throws IOException {
        for (Identifier id : new Identifier[] {
                ItemFeatureRenderer.ENCHANTED_GLINT_ITEM, ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR}) {
            byte[] original = readRgba(id);
            byte[] derived = original.clone();
            RemixEntitySubmitter.deriveGlintAlpha(derived);
            long alphaSum = 0;
            for (int i = 0; i < derived.length; i += 4) {
                assertEquals((byte) 255, original[i + 3], id + " is opaque in the jar");
                for (int c = 0; c < 3; c++) assertEquals(original[i + c], derived[i + c]);
                int max = Math.max(original[i] & 0xFF,
                        Math.max(original[i + 1] & 0xFF, original[i + 2] & 0xFF));
                assertEquals(max, derived[i + 3] & 0xFF);
                alphaSum += derived[i + 3] & 0xFF;
            }
            double meanAlpha = alphaSum / (double) (derived.length / 4) / 255.0;
            assertTrue(meanAlpha > 0.05 && meanAlpha < 0.75,
                    id + " mean derived alpha " + meanAlpha);
        }
    }

    @Test
    void onlyGlintLayersOverPlainTexturesDeriveAlpha() {
        Identifier glint = ItemFeatureRenderer.ENCHANTED_GLINT_ITEM;
        assertTrue(RemixEntitySubmitter.usesGlintAlpha(RenderLayerUtils.SurfaceMode.GLINT, glint));
        assertTrue(RemixEntitySubmitter.usesGlintAlpha(
                RenderLayerUtils.SurfaceMode.GLINT, ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR));
        for (RenderLayerUtils.SurfaceMode mode : RenderLayerUtils.SurfaceMode.values()) {
            if (mode == RenderLayerUtils.SurfaceMode.GLINT) continue;
            assertFalse(RemixEntitySubmitter.usesGlintAlpha(mode, glint), mode.name());
        }
        assertFalse(RemixEntitySubmitter.usesGlintAlpha(
                RenderLayerUtils.SurfaceMode.GLINT, TextureAtlas.LOCATION_ITEMS));
        assertFalse(RemixEntitySubmitter.usesGlintAlpha(
                RenderLayerUtils.SurfaceMode.GLINT, RenderLayerUtils.UNTEXTURED_LAYER_ID));
    }

    @Test
    void theGlintBlendKnobDefaultsToAlphaEmissive() {
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA_EMISSIVE, RemixEntitySubmitter.glintBlendType(null));
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA_EMISSIVE, RemixEntitySubmitter.glintBlendType(" "));
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA_EMISSIVE,
                RemixEntitySubmitter.glintBlendType("alphaEmissive"));
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_EMISSIVE, RemixEntitySubmitter.glintBlendType("emissive"));
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_EMISSIVE, RemixEntitySubmitter.glintBlendType(" emissive "));
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_COLOR_EMISSIVE,
                RemixEntitySubmitter.glintBlendType("colorEmissive"));
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA_EMISSIVE, RemixEntitySubmitter.glintBlendType("additive"));
    }

    @Test
    void theGlintIntensityKnobAcceptsOnlyFiniteNonNegativeNumbers() {
        float fallback = RemixEntitySubmitter.GLINT_EMISSIVE_INTENSITY;
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

    private static byte[] texels(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    /** Decodes a jar texture into the RGBA byte order {@code argbToRGBA} produces. */
    private static byte[] readRgba(Identifier id) throws IOException {
        String path = "/assets/" + id.getNamespace() + "/" + id.getPath();
        try (InputStream in = ItemFeatureRenderer.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " on the test classpath");
            BufferedImage image = ImageIO.read(in);
            int w = image.getWidth();
            int h = image.getHeight();
            byte[] out = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = image.getRGB(x, y);
                    int idx = (y * w + x) * 4;
                    out[idx] = (byte) (argb >> 16);
                    out[idx + 1] = (byte) (argb >> 8);
                    out[idx + 2] = (byte) argb;
                    out[idx + 3] = (byte) (argb >>> 24);
                }
            }
            return out;
        }
    }
}
