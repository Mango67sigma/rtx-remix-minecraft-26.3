/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes quads in Distant Horizons' 16-byte vertex layout, as its quad builder leaves them
 * in the buffers it uploads: native byte order, position 0, the payload as limit.
 */
final class DhQuadBuffer {

    private final ByteBuffer buffer;

    DhQuadBuffer(int maxQuads) {
        buffer = ByteBuffer.allocateDirect(maxQuads * LodGeometry.QUAD_BYTES).order(ByteOrder.nativeOrder());
    }

    /** A horizontal quad over {@code [x0, x1] x [z0, z1]} at height {@code y}. */
    DhQuadBuffer horizontal(int face, int x0, int z0, int x1, int z1, int y, int rgb, int material) {
        vertex(x0, y, z0, rgb, material, face);
        vertex(x0, y, z1, rgb, material, face);
        vertex(x1, y, z1, rgb, material, face);
        vertex(x1, y, z0, rgb, material, face);
        return this;
    }

    /** A face looking along z, on the plane {@code z}, over {@code [x0, x1] x [y0, y1]}. */
    DhQuadBuffer alongX(int face, int x0, int x1, int y0, int y1, int z, int rgbLow, int rgbHigh, int material) {
        vertex(x0, y0, z, rgbLow, material, face);
        vertex(x1, y0, z, rgbLow, material, face);
        vertex(x1, y1, z, rgbHigh, material, face);
        vertex(x0, y1, z, rgbHigh, material, face);
        return this;
    }

    /** A face looking along x, on the plane {@code x}, over {@code [z0, z1] x [y0, y1]}. */
    DhQuadBuffer alongZ(int face, int z0, int z1, int y0, int y1, int x, int rgbLow, int rgbHigh, int material) {
        vertex(x, y0, z0, rgbLow, material, face);
        vertex(x, y0, z1, rgbLow, material, face);
        vertex(x, y1, z1, rgbHigh, material, face);
        vertex(x, y1, z0, rgbHigh, material, face);
        return this;
    }

    /** Appends bytes that do not make a whole quad. */
    DhQuadBuffer tail(int bytes) {
        for (int i = 0; i < bytes; i++) buffer.put((byte) 0x7F);
        return this;
    }

    /** The written quads, ready to be copied. */
    ByteBuffer finish() {
        ByteBuffer written = buffer.duplicate().order(ByteOrder.nativeOrder());
        written.flip();
        return written;
    }

    private void vertex(int x, int y, int z, int rgb, int material, int face) {
        buffer.putShort((short) x);
        buffer.putShort((short) y);
        buffer.putShort((short) z);
        buffer.putShort((short) 0);
        buffer.put((byte) (rgb >> 16));
        buffer.put((byte) (rgb >> 8));
        buffer.put((byte) rgb);
        buffer.put((byte) 0x80);
        buffer.put((byte) material);
        buffer.put((byte) face);
        buffer.putShort((short) 0);
    }
}
