/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/**
 * Bakes vanilla's enchantment glint into an emission texture laid over a surface
 * texture's coordinates, so that a glinted surface carries its glint as its own emission.
 *
 * <p>Vanilla adds the glint in the surface's shader: it samples the glint texture at the
 * surface's texture coordinate taken through the glint's texture transform and adds the
 * square of the texel it finds to the lit colour ({@code item.fsh}, {@code entity.fsh}).
 * The bake evaluates that transform at time zero for every texel of the emission and stores
 * the squared texel, so the material adds what vanilla's shader added at that instant. The
 * scroll is not reproduced: the sheen is static.
 */
final class GlintEmission {

    /**
     * The largest side of a baked emission, in texels. The item glint spreads eight copies of
     * its 128-texel texture across a surface texture's coordinates, so 1024 texels keep every
     * glint texel; a larger surface texture, such as a high-resolution item atlas, shares each
     * emission texel between several of its own.
     */
    static final int MAX_SIZE = 1024;

    private static final int OPAQUE = 0xFF;
    private static final float COS = (float) Math.cos(RenderLayerUtils.Glint.ROTATION);
    private static final float SIN = (float) Math.sin(RenderLayerUtils.Glint.ROTATION);

    private GlintEmission() {}

    /**
     * The side of the emission baked along one axis of a {@code textureSize}-texel surface
     * texture: no coarser than the surface's own texels, so the sheen keeps to its pixels, and
     * no coarser than the glint texels the transform spreads along the axis, so no glint detail
     * is lost; at most {@link #MAX_SIZE}.
     *
     * @param scale     the glint transform's scale, {@link RenderLayerUtils.Glint#scale}
     * @param glintSize the glint texture's side, in texels
     */
    static int size(int textureSize, float scale, int glintSize) {
        int glintTexels = (int) Math.ceil(scale * glintSize);
        return Math.clamp(Math.max(textureSize, glintTexels), 1, MAX_SIZE);
    }

    /**
     * The glint's additive term over the coordinates of a surface texture: texel (x, y) holds
     * the square of the glint texel vanilla's transform takes the surface coordinate at that
     * texel's centre to, sampled bilinearly with wrap-around, as vanilla samples its blurred,
     * repeating glint. RGBA bytes, row-major from the top, alpha opaque.
     *
     * @param glintRgba the glint texture as RGBA bytes, row-major from the top
     * @param scale     the glint transform's scale, {@link RenderLayerUtils.Glint#scale}
     */
    static byte[] bake(byte[] glintRgba, int glintWidth, int glintHeight, float scale, int width, int height) {
        byte[] out = new byte[width * height * 4];
        float[] coordinate = new float[2];
        float[] texel = new float[3];
        for (int y = 0; y < height; y++) {
            float v = (y + 0.5f) / height;
            for (int x = 0; x < width; x++) {
                glintCoordinate((x + 0.5f) / width, v, scale, coordinate);
                sample(glintRgba, glintWidth, glintHeight, coordinate[0], coordinate[1], texel);
                int o = (y * width + x) * 4;
                out[o] = square(texel[0]);
                out[o + 1] = square(texel[1]);
                out[o + 2] = square(texel[2]);
                out[o + 3] = (byte) OPAQUE;
            }
        }
        return out;
    }

    /**
     * The glint coordinate vanilla's transform takes surface coordinate ({@code u}, {@code v})
     * to at time zero: scaled by {@code scale}, then turned by
     * {@link RenderLayerUtils.Glint#ROTATION} ({@code TextureTransform.setupGlintTexturing},
     * whose scroll translation is zero at time zero).
     */
    static void glintCoordinate(float u, float v, float scale, float[] out) {
        float su = scale * u;
        float sv = scale * v;
        out[0] = su * COS - sv * SIN;
        out[1] = su * SIN + sv * COS;
    }

    /**
     * Samples the RGB of a repeating texture at coordinate ({@code u}, {@code v}) with
     * bilinear filtering between texel centres, into {@code out} in [0, 255].
     */
    static void sample(byte[] rgba, int width, int height, float u, float v, float[] out) {
        float fx = u * width - 0.5f;
        float fy = v * height - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float tx = fx - x0;
        float ty = fy - y0;
        int left = Math.floorMod(x0, width);
        int right = Math.floorMod(x0 + 1, width);
        int top = Math.floorMod(y0, height);
        int bottom = Math.floorMod(y0 + 1, height);
        for (int c = 0; c < 3; c++) {
            float upper = lerp(channel(rgba, width, left, top, c), channel(rgba, width, right, top, c), tx);
            float lower = lerp(channel(rgba, width, left, bottom, c), channel(rgba, width, right, bottom, c), tx);
            out[c] = lerp(upper, lower, ty);
        }
    }

    private static float channel(byte[] rgba, int width, int x, int y, int c) {
        return rgba[(y * width + x) * 4 + c] & 0xFF;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** {@code c * c / 255}, rounded, for a channel value in [0, 255]. */
    private static byte square(float c) {
        return (byte) Math.round(c * c / OPAQUE);
    }
}
