/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Turns one LOD section's quads into a Remix mesh in section-local space; the instance
 * transform places it at the section's minimum corner.
 *
 * <p>The input is Distant Horizons' 16-byte vertex, the layout Iris mirrors and Distant
 * Horizons therefore keeps stable, in native byte order: unsigned 16-bit x, y and z in
 * blocks from the section's minimum corner (y from the level's minimum height), a light
 * and micro-offset short, RGBA colour, a block material id, the face index (down, up,
 * north, south, west, east) and a texture tile id. Four vertices make one axis-aligned
 * quad, and the colour is the whole albedo: the mesh samples a white texel.
 *
 * <p>Quads over chunk columns Minecraft's terrain draws are cut away, a quad the mod merged
 * across chunk borders split along them, so the two terrains never draw the same ground.
 * A side face belongs to the column behind it. What still overlaps, such as a mesh built
 * against a clip that has since moved on, sits {@link #SINK} below Minecraft's surfaces.
 * Water is its own surface with the water material, tiled in world space as Minecraft's
 * water is.
 *
 * <p>Runs on builder threads; it only fills pooled buffers, never calls into Remix.
 *
 * <p>The quad decoding, the chunk-border split and the side-face ownership follow
 * {@code LodMeshBuilder} in kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
final class LodMeshBuilder {

    static final int VERTEX_BYTES = 16;
    /** {@code EDhApiBlockMaterial.WATER}. */
    static final int WATER_MATERIAL = 12;
    /** How far every LOD surface sits below where the mod puts it. */
    static final float SINK = 1f / 32f;
    /** LOD water fills its block; a still water surface lies this much lower. */
    static final float WATER_SINK = 0.125f;

    static final int DOWN = 0;
    static final int UP = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;
    static final int WEST = 4;
    static final int EAST = 5;

    private static final int QUAD_BYTES = LodGeometry.QUAD_BYTES;
    private static final int STRIDE = HardcodedVertex.STRIDE;
    private static final int INDEX_BYTES_PER_QUAD = 6 * Integer.BYTES;
    private static final int OPAQUE = 0xFF000000;
    private static final float GROUND_UV = 0.5f;
    private static final int WATER_BOUNDS_MIN = HardcodedVertex.packHalf2x16(0f, 0f);
    private static final int WATER_BOUNDS_MAX = HardcodedVertex.packHalf2x16(1f, 1f);
    /** Quads a writer starts with when it expects fewer. */
    private static final int MIN_QUADS = 64;

    private LodMeshBuilder() {}

    /**
     * Builds the section's mesh, or returns null when the clip leaves nothing. A null
     * {@code water} material draws water as ground.
     *
     * @param originX world block x of the section's minimum corner
     * @param originY the level's minimum height, which the quads' y counts from
     * @param originZ world block z of the section's minimum corner
     */
    static RemixMesh build(LodGeometry geometry, LodClip clip, int originX, int originY, int originZ,
                           RemixMaterial ground, RemixMaterial water) {
        int waterQuads = water != null ? countWaterQuads(geometry) : 0;
        SurfaceWriter groundOut = new SurfaceWriter(false, originX, originY, originZ,
                geometry.quadCount() - waterQuads);
        SurfaceWriter waterOut = water != null
                ? new SurfaceWriter(true, originX, originY, originZ, waterQuads)
                : groundOut;
        RemixMeshSurface groundSurface = null;
        RemixMeshSurface waterSurface = null;
        try {
            convert(geometry, clip, originX, originZ, groundOut, waterOut);
            if (groundOut.quads > 0) groundSurface = groundOut.toSurface(ground);
            if (waterOut != groundOut && waterOut.quads > 0) waterSurface = waterOut.toSurface(water);
            RemixMeshSurface[] surfaces;
            if (groundSurface != null && waterSurface != null) {
                surfaces = new RemixMeshSurface[] {groundSurface, waterSurface};
            } else if (groundSurface != null) {
                surfaces = new RemixMeshSurface[] {groundSurface};
            } else if (waterSurface != null) {
                surfaces = new RemixMeshSurface[] {waterSurface};
            } else {
                return null;
            }
            RemixMesh mesh = new RemixMesh(surfaces);
            groundSurface = null;
            waterSurface = null;
            return mesh;
        } finally {
            if (groundSurface != null) groundSurface.disposeData();
            if (waterSurface != null) waterSurface.disposeData();
            groundOut.release();
            if (waterOut != groundOut) waterOut.release();
        }
    }

    /** Writes every quad of the geometry the clip leaves into its surface. */
    static void convert(LodGeometry geometry, LodClip clip, int originX, int originZ,
                        SurfaceWriter ground, SurfaceWriter water) {
        ByteBuffer data = geometry.data();
        if (data == null) return;
        ByteBuffer quads = data.duplicate().order(ByteOrder.nativeOrder());
        for (int quad = 0, count = geometry.quadCount(); quad < count; quad++) {
            int base = quad * QUAD_BYTES;
            int face = quads.get(base + 13) & 0xFF;
            if (face > EAST) continue;

            int x0 = Integer.MAX_VALUE;
            int y0 = Integer.MAX_VALUE;
            int z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            int y1 = Integer.MIN_VALUE;
            int z1 = Integer.MIN_VALUE;
            for (int v = 0; v < 4; v++) {
                int at = base + v * VERTEX_BYTES;
                int x = quads.getShort(at) & 0xFFFF;
                int y = quads.getShort(at + 2) & 0xFFFF;
                int z = quads.getShort(at + 4) & 0xFFFF;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
                z0 = Math.min(z0, z);
                z1 = Math.max(z1, z);
            }
            if (!spansFace(face, x0, x1, y0, y1, z0, z1)) continue;

            // A grass side fades from dirt at its foot to grass at its top, so the colour
            // is kept per edge rather than per quad.
            int colorLow = 0;
            int colorHigh = 0;
            for (int v = 3; v >= 0; v--) {
                int at = base + v * VERTEX_BYTES;
                int y = quads.getShort(at + 2) & 0xFFFF;
                int color = OPAQUE
                        | (quads.get(at + 8) & 0xFF) << 16
                        | (quads.get(at + 9) & 0xFF) << 8
                        | (quads.get(at + 10) & 0xFF);
                if (y == y0) colorLow = color;
                if (y == y1) colorHigh = color;
            }

            SurfaceWriter out = (quads.get(base + 12) & 0xFF) == WATER_MATERIAL ? water : ground;
            emitClipped(out, clip, face, x0, x1, y0, y1, z0, z1, colorLow, colorHigh, originX, originZ);
        }
    }

    private static int countWaterQuads(LodGeometry geometry) {
        ByteBuffer data = geometry.data();
        if (data == null) return 0;
        int water = 0;
        for (int quad = 0, count = geometry.quadCount(); quad < count; quad++) {
            if ((data.get(quad * QUAD_BYTES + 12) & 0xFF) == WATER_MATERIAL) water++;
        }
        return water;
    }

    /** Whether the bounds make an axis-aligned rectangle facing {@code face}. */
    private static boolean spansFace(int face, int x0, int x1, int y0, int y1, int z0, int z1) {
        return switch (face) {
            case DOWN, UP -> y0 == y1 && x0 < x1 && z0 < z1;
            case NORTH, SOUTH -> z0 == z1 && x0 < x1 && y0 < y1;
            default -> x0 == x1 && z0 < z1 && y0 < y1;
        };
    }

    /**
     * Emits what the clip leaves of one quad, given in section-local blocks. Chunks are
     * tested in world coordinates; a side face is tested in the column behind it, so a face
     * on a chunk border goes with the column it bounds.
     */
    private static void emitClipped(SurfaceWriter out, LodClip clip, int face,
                                    int x0, int x1, int y0, int y1, int z0, int z1,
                                    int colorLow, int colorHigh, int originX, int originZ) {
        int wx0 = originX + x0;
        int wx1 = originX + x1;
        int wz0 = originZ + z0;
        int wz1 = originZ + z1;
        switch (face) {
            case DOWN, UP -> {
                int cx0 = wx0 >> 4;
                int cx1 = (wx1 - 1) >> 4;
                int cz0 = wz0 >> 4;
                int cz1 = (wz1 - 1) >> 4;
                if (!clip.clipsAny(cx0, cx1, cz0, cz1)) {
                    out.quad(face, x0, x1, y0, y1, z0, z1, colorLow, colorHigh);
                    return;
                }
                for (int cz = cz0; cz <= cz1; cz++) {
                    int rowZ0 = Math.max(wz0, cz << 4) - originZ;
                    int rowZ1 = Math.min(wz1, (cz + 1) << 4) - originZ;
                    int cx = cx0;
                    while (cx <= cx1) {
                        if (clip.clips(cx, cz)) {
                            cx++;
                            continue;
                        }
                        int end = cx;
                        while (end < cx1 && !clip.clips(end + 1, cz)) end++;
                        out.quad(face, Math.max(wx0, cx << 4) - originX, Math.min(wx1, (end + 1) << 4) - originX,
                                y0, y1, rowZ0, rowZ1, colorLow, colorHigh);
                        cx = end + 1;
                    }
                }
            }
            case NORTH, SOUTH -> {
                // A face looking toward negative z lies on its column's minimum edge.
                int cz = (face == NORTH ? wz0 : wz0 - 1) >> 4;
                int cx0 = wx0 >> 4;
                int cx1 = (wx1 - 1) >> 4;
                if (!clip.clipsAny(cx0, cx1, cz, cz)) {
                    out.quad(face, x0, x1, y0, y1, z0, z1, colorLow, colorHigh);
                    return;
                }
                int cx = cx0;
                while (cx <= cx1) {
                    if (clip.clips(cx, cz)) {
                        cx++;
                        continue;
                    }
                    int end = cx;
                    while (end < cx1 && !clip.clips(end + 1, cz)) end++;
                    out.quad(face, Math.max(wx0, cx << 4) - originX, Math.min(wx1, (end + 1) << 4) - originX,
                            y0, y1, z0, z1, colorLow, colorHigh);
                    cx = end + 1;
                }
            }
            default -> {
                // A face looking toward negative x lies on its column's minimum edge.
                int cx = (face == WEST ? wx0 : wx0 - 1) >> 4;
                int cz0 = wz0 >> 4;
                int cz1 = (wz1 - 1) >> 4;
                if (!clip.clipsAny(cx, cx, cz0, cz1)) {
                    out.quad(face, x0, x1, y0, y1, z0, z1, colorLow, colorHigh);
                    return;
                }
                int cz = cz0;
                while (cz <= cz1) {
                    if (clip.clips(cx, cz)) {
                        cz++;
                        continue;
                    }
                    int end = cz;
                    while (end < cz1 && !clip.clips(cx, end + 1)) end++;
                    out.quad(face, x0, x1, y0, y1, Math.max(wz0, cz << 4) - originZ,
                            Math.min(wz1, (end + 1) << 4) - originZ, colorLow, colorHigh);
                    cz = end + 1;
                }
            }
        }
    }

    /** Growable vertex and index data for one surface, in {@link HardcodedVertex} layout. */
    static final class SurfaceWriter {
        final boolean water;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final int expectedQuads;
        private ByteBuffer vertices;
        private ByteBuffer indices;
        int quads;

        SurfaceWriter(boolean water, int originX, int originY, int originZ, int expectedQuads) {
            this.water = water;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.expectedQuads = Math.max(MIN_QUADS, expectedQuads);
        }

        /**
         * Appends one quad facing {@code face} over section-local bounds, wound
         * counter-clockwise seen from the side it faces. {@code colorLow} goes to the
         * corners at {@code y0} and {@code colorHigh} to those at {@code y1}.
         */
        void quad(int face, int x0, int x1, int y0, int y1, int z0, int z1, int colorLow, int colorHigh) {
            ensureCapacity();
            float sink = water ? SINK + WATER_SINK : SINK;
            float bottom = y0 - sink;
            float top = y1 - sink;
            int first = quads * 4;
            switch (face) {
                case UP -> {
                    vertex(first, x0, top, z0, 0f, 1f, 0f, colorHigh, face);
                    vertex(first + 1, x0, top, z1, 0f, 1f, 0f, colorHigh, face);
                    vertex(first + 2, x1, top, z1, 0f, 1f, 0f, colorHigh, face);
                    vertex(first + 3, x1, top, z0, 0f, 1f, 0f, colorHigh, face);
                }
                case DOWN -> {
                    vertex(first, x0, bottom, z0, 0f, -1f, 0f, colorLow, face);
                    vertex(first + 1, x1, bottom, z0, 0f, -1f, 0f, colorLow, face);
                    vertex(first + 2, x1, bottom, z1, 0f, -1f, 0f, colorLow, face);
                    vertex(first + 3, x0, bottom, z1, 0f, -1f, 0f, colorLow, face);
                }
                case SOUTH -> {
                    vertex(first, x0, bottom, z0, 0f, 0f, 1f, colorLow, face);
                    vertex(first + 1, x1, bottom, z0, 0f, 0f, 1f, colorLow, face);
                    vertex(first + 2, x1, top, z0, 0f, 0f, 1f, colorHigh, face);
                    vertex(first + 3, x0, top, z0, 0f, 0f, 1f, colorHigh, face);
                }
                case NORTH -> {
                    vertex(first, x1, bottom, z0, 0f, 0f, -1f, colorLow, face);
                    vertex(first + 1, x0, bottom, z0, 0f, 0f, -1f, colorLow, face);
                    vertex(first + 2, x0, top, z0, 0f, 0f, -1f, colorHigh, face);
                    vertex(first + 3, x1, top, z0, 0f, 0f, -1f, colorHigh, face);
                }
                case EAST -> {
                    vertex(first, x0, bottom, z1, 1f, 0f, 0f, colorLow, face);
                    vertex(first + 1, x0, bottom, z0, 1f, 0f, 0f, colorLow, face);
                    vertex(first + 2, x0, top, z0, 1f, 0f, 0f, colorHigh, face);
                    vertex(first + 3, x0, top, z1, 1f, 0f, 0f, colorHigh, face);
                }
                default -> {
                    vertex(first, x0, bottom, z0, -1f, 0f, 0f, colorLow, face);
                    vertex(first + 1, x0, bottom, z1, -1f, 0f, 0f, colorLow, face);
                    vertex(first + 2, x0, top, z1, -1f, 0f, 0f, colorHigh, face);
                    vertex(first + 3, x0, top, z0, -1f, 0f, 0f, colorHigh, face);
                }
            }
            int at = quads * INDEX_BYTES_PER_QUAD;
            indices.putInt(at, first);
            indices.putInt(at + 4, first + 1);
            indices.putInt(at + 8, first + 2);
            indices.putInt(at + 12, first);
            indices.putInt(at + 16, first + 2);
            indices.putInt(at + 20, first + 3);
            quads++;
        }

        private void vertex(int index, float x, float y, float z, float nx, float ny, float nz,
                            int color, int face) {
            int at = index * STRIDE;
            vertices.putFloat(at, x);
            vertices.putFloat(at + 4, y);
            vertices.putFloat(at + 8, z);
            vertices.putFloat(at + 12, nx);
            vertices.putFloat(at + 16, ny);
            vertices.putFloat(at + 20, nz);
            if (water) {
                // Tiled in world space along the face's own axes, as Minecraft's water is.
                float tile = BlockTextureManager.getWaterTileSize();
                float wx = originX + x;
                float wy = originY + y;
                float wz = originZ + z;
                float u;
                float v;
                switch (face) {
                    case DOWN, UP -> {
                        u = wx;
                        v = wz;
                    }
                    case NORTH, SOUTH -> {
                        u = wx;
                        v = wy;
                    }
                    default -> {
                        u = wz;
                        v = wy;
                    }
                }
                vertices.putFloat(at + 24, u / tile);
                vertices.putFloat(at + 28, v / tile);
                vertices.putInt(at + 32, color);
                vertices.putInt(at + 36, WATER_BOUNDS_MIN);
                vertices.putInt(at + 40, WATER_BOUNDS_MAX);
            } else {
                vertices.putFloat(at + 24, GROUND_UV);
                vertices.putFloat(at + 28, GROUND_UV);
                vertices.putInt(at + 32, color);
                vertices.putInt(at + 36, 0);
                vertices.putInt(at + 40, 0);
            }
            for (int pad = 44; pad < STRIDE; pad += 4) vertices.putInt(at + pad, 0);
        }

        private void ensureCapacity() {
            if (vertices == null) {
                vertices = DirectBufferPool.acquire(Math.multiplyExact(expectedQuads, 4 * STRIDE))
                        .order(ByteOrder.LITTLE_ENDIAN);
                indices = DirectBufferPool.acquire(Math.multiplyExact(expectedQuads, INDEX_BYTES_PER_QUAD))
                        .order(ByteOrder.LITTLE_ENDIAN);
            }
            vertices = grow(vertices, (quads + 1) * 4 * STRIDE, quads * 4 * STRIDE);
            indices = grow(indices, (quads + 1) * INDEX_BYTES_PER_QUAD, quads * INDEX_BYTES_PER_QUAD);
        }

        private static ByteBuffer grow(ByteBuffer buffer, int needed, int used) {
            if (buffer.capacity() >= needed) return buffer;
            ByteBuffer grown = DirectBufferPool.acquire(Math.max(needed, Math.multiplyExact(buffer.capacity(), 2)))
                    .order(ByteOrder.LITTLE_ENDIAN);
            grown.put(0, buffer, 0, used);
            DirectBufferPool.release(buffer);
            return grown;
        }

        /** Hands the written data to a new surface, which then owns it. */
        RemixMeshSurface toSurface(RemixMaterial material) {
            RemixMeshSurface surface = new RemixMeshSurface(material)
                    .setVertexData(vertices, quads * 4)
                    .setIndexData(indices, quads * 6);
            if (water) surface.setFlags(MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS);
            vertices = null;
            indices = null;
            return surface;
        }

        /** Returns whatever data no surface took over. */
        void release() {
            DirectBufferPool.release(vertices);
            DirectBufferPool.release(indices);
            vertices = null;
            indices = null;
        }
    }
}
