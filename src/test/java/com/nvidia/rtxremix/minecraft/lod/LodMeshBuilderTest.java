/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.nvidia.rtxremix.minecraft.lod.LodMeshBuilder.DOWN;
import static com.nvidia.rtxremix.minecraft.lod.LodMeshBuilder.EAST;
import static com.nvidia.rtxremix.minecraft.lod.LodMeshBuilder.NORTH;
import static com.nvidia.rtxremix.minecraft.lod.LodMeshBuilder.SINK;
import static com.nvidia.rtxremix.minecraft.lod.LodMeshBuilder.SOUTH;
import static com.nvidia.rtxremix.minecraft.lod.LodMeshBuilder.UP;
import static com.nvidia.rtxremix.minecraft.lod.LodMeshBuilder.WATER_MATERIAL;
import static com.nvidia.rtxremix.minecraft.lod.LodMeshBuilder.WATER_SINK;
import static com.nvidia.rtxremix.minecraft.lod.LodMeshBuilder.WEST;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodMeshBuilderTest {

    private static final int STONE = 2;
    private static final int GRASS_RGB = 0x5C9E33;
    private static final int DIRT_RGB = 0x866043;
    private static final int WATER_RGB = 0x3F76E4;
    private static final float EPSILON = 1e-5f;

    private final RemixMaterial ground = new RemixMaterial((MaterialInfo) null);
    private final RemixMaterial water = new RemixMaterial((MaterialInfo) null);

    @Test
    void anUpFaceKeepsItsBoundsAndColourAndSinksBelowMinecraftsSurface() throws Exception {
        LodGeometry geometry = geometry(new DhQuadBuffer(1).horizontal(UP, 0, 0, 16, 16, 80, GRASS_RGB, STONE));
        RemixMesh mesh = LodMeshBuilder.build(geometry, LodClip.NONE, 32, -64, -16, ground, water);
        try {
            List<Surface> surfaces = surfaces(mesh);
            assertEquals(1, surfaces.size());
            Surface surface = surfaces.get(0);
            assertSame(ground, surface.material);
            assertEquals(0, surface.flags);
            assertEquals(1, surface.quadCount());
            Quad quad = surface.quad(0);
            assertArrayEquals(new float[] {0, 16, 80 - SINK, 80 - SINK, 0, 16}, quad.bounds(), EPSILON);
            for (Vertex vertex : quad.vertices) {
                assertArrayEquals(new float[] {0, 1, 0}, vertex.normal, EPSILON);
                assertEquals(0xFF000000 | GRASS_RGB, vertex.color);
                assertArrayEquals(new float[] {0.5f, 0.5f}, vertex.uv, EPSILON);
                assertEquals(0, vertex.boundsMin);
                assertEquals(0, vertex.boundsMax);
            }
            assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, surface.indices(0));
        } finally {
            mesh.disposeData();
            geometry.release();
        }
    }

    @Test
    void normalsFollowTheFaceIndexAndWindingFacesThem() throws Exception {
        LodGeometry geometry = geometry(new DhQuadBuffer(6)
                .horizontal(DOWN, 0, 0, 8, 8, 10, DIRT_RGB, STONE)
                .horizontal(UP, 0, 0, 8, 8, 20, GRASS_RGB, STONE)
                .alongX(NORTH, 0, 8, 10, 20, 0, DIRT_RGB, GRASS_RGB, STONE)
                .alongX(SOUTH, 0, 8, 10, 20, 8, DIRT_RGB, GRASS_RGB, STONE)
                .alongZ(WEST, 0, 8, 10, 20, 0, DIRT_RGB, GRASS_RGB, STONE)
                .alongZ(EAST, 0, 8, 10, 20, 8, DIRT_RGB, GRASS_RGB, STONE));
        RemixMesh mesh = LodMeshBuilder.build(geometry, LodClip.NONE, 0, 0, 0, ground, water);
        try {
            Surface surface = surfaces(mesh).get(0);
            float[][] expected = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
            assertEquals(expected.length, surface.quadCount());
            for (int face = 0; face < expected.length; face++) {
                Quad quad = surface.quad(face);
                for (Vertex vertex : quad.vertices) {
                    assertArrayEquals(expected[face], vertex.normal, EPSILON, "face " + face);
                }
                assertArrayEquals(expected[face], quad.windingNormal(), EPSILON, "winding of face " + face);
            }
        } finally {
            mesh.disposeData();
            geometry.release();
        }
    }

    @Test
    void waterIsItsOwnSurfaceTiledInWorldSpaceAtAStillWaterHeight() throws Exception {
        LodGeometry geometry = geometry(new DhQuadBuffer(2)
                .horizontal(UP, 0, 0, 16, 16, 63, GRASS_RGB, STONE)
                .horizontal(UP, 16, 0, 32, 16, 63, WATER_RGB, WATER_MATERIAL));
        RemixMesh mesh = LodMeshBuilder.build(geometry, LodClip.NONE, 32, -64, -16, ground, water);
        try {
            List<Surface> surfaces = surfaces(mesh);
            assertEquals(2, surfaces.size());
            assertSame(ground, surfaces.get(0).material);
            Surface waterSurface = surfaces.get(1);
            assertSame(water, waterSurface.material);
            assertEquals(MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS, waterSurface.flags);
            assertEquals(1, waterSurface.quadCount());
            float tile = BlockTextureManager.getWaterTileSize();
            for (Vertex vertex : waterSurface.quad(0).vertices) {
                assertEquals(63 - SINK - WATER_SINK, vertex.position[1], EPSILON);
                assertEquals((32 + vertex.position[0]) / tile, vertex.uv[0], EPSILON);
                assertEquals((-16 + vertex.position[2]) / tile, vertex.uv[1], EPSILON);
                assertEquals(0xFF000000 | WATER_RGB, vertex.color);
                assertEquals(HardcodedVertex.packHalf2x16(0f, 0f), vertex.boundsMin);
                assertEquals(HardcodedVertex.packHalf2x16(1f, 1f), vertex.boundsMax);
            }
        } finally {
            mesh.disposeData();
            geometry.release();
        }
    }

    @Test
    void waterWithoutAWaterMaterialIsDrawnAsGround() throws Exception {
        LodGeometry geometry = geometry(new DhQuadBuffer(2)
                .horizontal(UP, 0, 0, 16, 16, 63, GRASS_RGB, STONE)
                .horizontal(UP, 16, 0, 32, 16, 63, WATER_RGB, WATER_MATERIAL));
        RemixMesh mesh = LodMeshBuilder.build(geometry, LodClip.NONE, 0, 0, 0, ground, null);
        try {
            List<Surface> surfaces = surfaces(mesh);
            assertEquals(1, surfaces.size());
            assertEquals(2, surfaces.get(0).quadCount());
            assertEquals(63 - SINK, surfaces.get(0).quad(1).vertices.get(0).position[1], EPSILON);
        } finally {
            mesh.disposeData();
            geometry.release();
        }
    }

    @Test
    void theClipCutsAMergedQuadAlongChunkBordersAroundDrawnColumns() throws Exception {
        LodGeometry geometry = geometry(new DhQuadBuffer(1).horizontal(UP, 0, 0, 64, 64, 70, GRASS_RGB, STONE));
        LodClip clip = clipOf(1, 1);
        RemixMesh mesh = LodMeshBuilder.build(geometry, clip, 0, -64, 0, ground, water);
        try {
            Surface surface = surfaces(mesh).get(0);
            float area = 0;
            for (int i = 0; i < surface.quadCount(); i++) {
                float[] b = surface.quad(i).bounds();
                area += (b[1] - b[0]) * (b[5] - b[4]);
                boolean overlapsDrawnChunk = b[0] < 32 && b[1] > 16 && b[4] < 32 && b[5] > 16;
                assertFalse(overlapsDrawnChunk, "quad " + i + " lies over the drawn column");
                assertEquals(Math.floor(b[4] / 16), Math.floor((b[5] - 1) / 16), "quad " + i + " spans one chunk row");
            }
            assertEquals(64 * 64 - 16 * 16, area, EPSILON);
            assertEquals(5, surface.quadCount(), "a full row, the row around the hole in two, two full rows");
        } finally {
            mesh.disposeData();
            geometry.release();
        }
    }

    @Test
    void aQuadOutsideEveryDrawnColumnStaysWhole() throws Exception {
        LodGeometry geometry = geometry(new DhQuadBuffer(1).horizontal(UP, 0, 0, 64, 32, 70, GRASS_RGB, STONE));
        RemixMesh mesh = LodMeshBuilder.build(geometry, clipOf(3, 3), 0, -64, 0, ground, water);
        try {
            Surface surface = surfaces(mesh).get(0);
            assertEquals(1, surface.quadCount());
            assertArrayEquals(new float[] {0, 64, 70 - SINK, 70 - SINK, 0, 32}, surface.quad(0).bounds(), EPSILON);
        } finally {
            mesh.disposeData();
            geometry.release();
        }
    }

    @Test
    void aSideFaceOnAChunkBorderGoesWithTheColumnBehindIt() throws Exception {
        // North and west faces lie on their column's minimum edge, south and east on its maximum.
        assertSideFaceOwner(new DhQuadBuffer(1).alongX(NORTH, 0, 16, 60, 70, 16, DIRT_RGB, GRASS_RGB, STONE), 0, 1, 0, 0);
        assertSideFaceOwner(new DhQuadBuffer(1).alongX(SOUTH, 0, 16, 60, 70, 16, DIRT_RGB, GRASS_RGB, STONE), 0, 0, 0, 1);
        assertSideFaceOwner(new DhQuadBuffer(1).alongZ(WEST, 0, 16, 60, 70, 16, DIRT_RGB, GRASS_RGB, STONE), 1, 0, 0, 0);
        assertSideFaceOwner(new DhQuadBuffer(1).alongZ(EAST, 0, 16, 60, 70, 16, DIRT_RGB, GRASS_RGB, STONE), 0, 0, 1, 0);
    }

    @Test
    void aClippedSideFaceSplitsAlongChunkBorders() throws Exception {
        LodGeometry geometry = geometry(new DhQuadBuffer(1).alongX(SOUTH, 0, 48, 60, 70, 8, DIRT_RGB, GRASS_RGB, STONE));
        RemixMesh mesh = LodMeshBuilder.build(geometry, clipOf(1, 0), 0, -64, 0, ground, water);
        try {
            Surface surface = surfaces(mesh).get(0);
            assertEquals(2, surface.quadCount());
            assertArrayEquals(new float[] {0, 16, 60 - SINK, 70 - SINK, 8, 8}, surface.quad(0).bounds(), EPSILON);
            assertArrayEquals(new float[] {32, 48, 60 - SINK, 70 - SINK, 8, 8}, surface.quad(1).bounds(), EPSILON);
        } finally {
            mesh.disposeData();
            geometry.release();
        }
    }

    @Test
    void aSectionMinecraftWhollyDrawsBuildsNoMesh() {
        LodGeometry geometry = geometry(new DhQuadBuffer(1).horizontal(UP, 0, 0, 32, 32, 70, GRASS_RGB, STONE));
        LodClip clip = LodClip.around(0, 0, 4, (x, z) -> true);
        try {
            assertNull(LodMeshBuilder.build(geometry, clip, 0, -64, 0, ground, water));
            assertTrue(clip.covers(new LodSection(new LodCapture(), null, 0, -64, 0, 32)));
        } finally {
            geometry.release();
        }
    }

    @Test
    void aGrassSideFadesFromItsFootToItsTop() throws Exception {
        LodGeometry geometry = geometry(new DhQuadBuffer(1).alongZ(EAST, 0, 4, 60, 64, 4, DIRT_RGB, GRASS_RGB, STONE));
        RemixMesh mesh = LodMeshBuilder.build(geometry, LodClip.NONE, 0, -64, 0, ground, water);
        try {
            for (Vertex vertex : surfaces(mesh).get(0).quad(0).vertices) {
                int expected = vertex.position[1] < 62 ? DIRT_RGB : GRASS_RGB;
                assertEquals(0xFF000000 | expected, vertex.color);
            }
        } finally {
            mesh.disposeData();
            geometry.release();
        }
    }

    @Test
    void malformedQuadsAreSkipped() throws Exception {
        LodGeometry geometry = geometry(new DhQuadBuffer(3)
                .horizontal(9, 0, 0, 16, 16, 70, GRASS_RGB, STONE)
                .horizontal(UP, 0, 0, 0, 16, 70, GRASS_RGB, STONE)
                .horizontal(UP, 0, 0, 16, 16, 70, GRASS_RGB, STONE));
        RemixMesh mesh = LodMeshBuilder.build(geometry, LodClip.NONE, 0, -64, 0, ground, water);
        try {
            assertEquals(1, surfaces(mesh).get(0).quadCount());
        } finally {
            mesh.disposeData();
            geometry.release();
        }
    }

    @Test
    void aSectionWithoutQuadsBuildsNoMesh() {
        LodGeometry geometry = LodGeometry.copyOf(List.of(), List.of(), 1);
        assertNull(geometry.data());
        assertNull(LodMeshBuilder.build(geometry, LodClip.NONE, 0, -64, 0, ground, water));
        geometry.release();
    }

    @Test
    void theCopyKeepsWholeQuadsAndLeavesTheModsBuffersAsTheyWere() {
        ByteBuffer opaque = new DhQuadBuffer(3)
                .horizontal(UP, 0, 0, 16, 16, 70, GRASS_RGB, STONE)
                .horizontal(UP, 16, 0, 32, 16, 70, GRASS_RGB, STONE)
                .tail(10)
                .finish();
        ByteBuffer transparent = new DhQuadBuffer(1)
                .horizontal(UP, 0, 16, 16, 32, 63, WATER_RGB, WATER_MATERIAL)
                .finish();
        int opaqueLimit = opaque.limit();
        LodGeometry geometry = LodGeometry.copyOf(List.of(opaque), List.of(transparent), 7);
        try {
            assertEquals(3, geometry.quadCount());
            assertEquals(7, geometry.generation());
            assertEquals(0, opaque.position());
            assertEquals(opaqueLimit, opaque.limit());
            assertEquals(0, transparent.position());
            ByteBuffer copy = geometry.data().duplicate().order(ByteOrder.nativeOrder());
            assertEquals(WATER_MATERIAL, copy.get(2 * LodGeometry.QUAD_BYTES + 12));
        } finally {
            geometry.release();
        }
    }

    // --- helpers ---

    private void assertSideFaceOwner(DhQuadBuffer quad, int ownerX, int ownerZ, int neighbourX, int neighbourZ)
            throws Exception {
        LodGeometry geometry = geometry(quad);
        try {
            assertNull(LodMeshBuilder.build(geometry, clipOf(ownerX, ownerZ), 0, -64, 0, ground, water),
                    "a drawn owner column removes the face");
            RemixMesh kept = LodMeshBuilder.build(geometry, clipOf(neighbourX, neighbourZ), 0, -64, 0, ground, water);
            assertNotNull(kept, "a drawn neighbour leaves the face");
            assertEquals(1, surfaces(kept).get(0).quadCount());
            kept.disposeData();
        } finally {
            geometry.release();
        }
    }

    private static LodGeometry geometry(DhQuadBuffer quads) {
        return LodGeometry.copyOf(List.of(quads.finish()), List.of(), 1);
    }

    /** Clips exactly the given chunks, as {@code x0, z0, x1, z1, ...}. */
    private static LodClip clipOf(int... chunks) {
        Set<Long> drawn = new java.util.HashSet<>();
        for (int i = 0; i < chunks.length; i += 2) drawn.add(key(chunks[i], chunks[i + 1]));
        return LodClip.around(0, 0, 8, (x, z) -> drawn.contains(key(x, z)));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static List<Surface> surfaces(RemixMesh mesh) throws Exception {
        Field field = RemixMesh.class.getDeclaredField("surfaces");
        field.setAccessible(true);
        List<Surface> surfaces = new ArrayList<>();
        for (RemixMeshSurface surface : (RemixMeshSurface[]) field.get(mesh)) surfaces.add(new Surface(surface));
        return surfaces;
    }

    private static Object read(RemixMeshSurface surface, String name) throws Exception {
        Field field = RemixMeshSurface.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(surface);
    }

    private record Vertex(float[] position, float[] normal, float[] uv, int color, int boundsMin, int boundsMax) {}

    private record Quad(List<Vertex> vertices) {
        /** minX, maxX, minY, maxY, minZ, maxZ. */
        float[] bounds() {
            float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE,
                    Float.MAX_VALUE, -Float.MAX_VALUE};
            for (Vertex vertex : vertices) {
                for (int axis = 0; axis < 3; axis++) {
                    b[axis * 2] = Math.min(b[axis * 2], vertex.position[axis]);
                    b[axis * 2 + 1] = Math.max(b[axis * 2 + 1], vertex.position[axis]);
                }
            }
            return b;
        }

        /** Unit normal of the first triangle's winding. */
        float[] windingNormal() {
            float[] a = vertices.get(0).position;
            float[] b = vertices.get(1).position;
            float[] c = vertices.get(2).position;
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            return new float[] {nx / length, ny / length, nz / length};
        }
    }

    private static final class Surface {
        final RemixMaterial material;
        final int flags;
        final ByteBuffer vertices;
        final int vertexCount;
        final ByteBuffer indices;

        Surface(RemixMeshSurface surface) throws Exception {
            material = surface.getMaterial();
            flags = (int) read(surface, "flags");
            vertices = ((ByteBuffer) read(surface, "vertexData")).duplicate().order(ByteOrder.LITTLE_ENDIAN);
            vertexCount = (int) read(surface, "vertexCount");
            indices = ((ByteBuffer) read(surface, "indexData")).duplicate().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(vertexCount / 4 * 6, (int) read(surface, "indexCount"));
        }

        int quadCount() {
            return vertexCount / 4;
        }

        Quad quad(int quad) {
            List<Vertex> corners = new ArrayList<>(4);
            for (int v = 0; v < 4; v++) {
                int at = (quad * 4 + v) * HardcodedVertex.STRIDE;
                corners.add(new Vertex(
                        new float[] {vertices.getFloat(at), vertices.getFloat(at + 4), vertices.getFloat(at + 8)},
                        new float[] {vertices.getFloat(at + 12), vertices.getFloat(at + 16), vertices.getFloat(at + 20)},
                        new float[] {vertices.getFloat(at + 24), vertices.getFloat(at + 28)},
                        vertices.getInt(at + 32), vertices.getInt(at + 36), vertices.getInt(at + 40)));
            }
            return new Quad(corners);
        }

        int[] indices(int quad) {
            int[] values = new int[6];
            for (int i = 0; i < 6; i++) values[i] = indices.getInt((quad * 6 + i) * Integer.BYTES) - quad * 4;
            return values;
        }
    }
}
