/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

/**
 * The chunk columns where Minecraft's own terrain is drawn, over a square window of them
 * around the camera. LOD terrain gives way in exactly these columns and nowhere else, so
 * it neither lies over Minecraft's terrain nor leaves a ring of neither where Minecraft has
 * not drawn a column yet.
 *
 * <p>Immutable, so a builder thread reads the clip it was handed while the render thread
 * makes the next one. A section's {@link #signature} identifies which of its chunks a clip
 * removes, so equal signatures build equal meshes and a new clip rebuilds only the
 * sections whose share of it changed.
 *
 * <p>The window bitset and the per-section signature follow {@code LodMeshBuilder.Clip} in
 * kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
final class LodClip {

    /** Whether Minecraft's terrain draws a chunk column. */
    @FunctionalInterface
    interface ColumnTest {
        boolean drawn(int chunkX, int chunkZ);
    }

    /** Clips nothing. */
    static final LodClip NONE = new LodClip(0, 0, 0, new long[0]);

    private final int originX;
    private final int originZ;
    private final int size;
    private final long[] bits;

    private LodClip(int originX, int originZ, int size, long[] bits) {
        this.originX = originX;
        this.originZ = originZ;
        this.size = size;
        this.bits = bits;
    }

    /**
     * Records the columns {@code drawn} accepts within {@code radius} chunks of a centre
     * chunk, each way; nothing beyond that window is ever clipped.
     */
    static LodClip around(int centerX, int centerZ, int radius, ColumnTest drawn) {
        if (radius < 0) return NONE;
        int size = 2 * radius + 1;
        long[] bits = new long[(size * size + 63) >>> 6];
        int originX = centerX - radius;
        int originZ = centerZ - radius;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (drawn.drawn(originX + x, originZ + z)) {
                    int i = z * size + x;
                    bits[i >>> 6] |= 1L << i;
                }
            }
        }
        return new LodClip(originX, originZ, size, bits);
    }

    /** Whether LOD terrain gives way in a chunk column. */
    boolean clips(int chunkX, int chunkZ) {
        int x = chunkX - originX;
        int z = chunkZ - originZ;
        if (x < 0 || z < 0 || x >= size || z >= size) return false;
        int i = z * size + x;
        return (bits[i >>> 6] & 1L << i) != 0;
    }

    /** Whether any chunk of an inclusive chunk range is clipped. */
    boolean clipsAny(int chunkX0, int chunkX1, int chunkZ0, int chunkZ1) {
        int x0 = Math.max(chunkX0, originX);
        int x1 = Math.min(chunkX1, originX + size - 1);
        int z0 = Math.max(chunkZ0, originZ);
        int z1 = Math.min(chunkZ1, originZ + size - 1);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (clips(x, z)) return true;
            }
        }
        return false;
    }

    /** Whether the clip removes every chunk of a section's square, leaving its mesh empty. */
    boolean covers(LodSection section) {
        int x0 = section.minX >> 4;
        int x1 = (section.minX + section.width - 1) >> 4;
        int z0 = section.minZ >> 4;
        int z1 = (section.minZ + section.width - 1) >> 4;
        if (x0 < originX || z0 < originZ || x1 >= originX + size || z1 >= originZ + size) return false;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (!clips(x, z)) return false;
            }
        }
        return true;
    }

    /**
     * Identifies which chunks of a section's square the clip removes: 0 when it removes
     * none, and equal for two clips that remove the same ones.
     */
    long signature(LodSection section) {
        int x0 = Math.max(section.minX >> 4, originX);
        int x1 = Math.min((section.minX + section.width - 1) >> 4, originX + size - 1);
        int z0 = Math.max(section.minZ >> 4, originZ);
        int z1 = Math.min((section.minZ + section.width - 1) >> 4, originZ + size - 1);
        long hash = 0;
        boolean any = false;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (!clips(x, z)) continue;
                any = true;
                hash = (hash ^ mix(((long) x << 32) ^ (z & 0xFFFFFFFFL))) * 0x9E3779B97F4A7C15L;
            }
        }
        if (!any) return 0;
        return hash != 0 ? hash : 1;
    }

    private static long mix(long key) {
        key ^= key >>> 33;
        key *= 0xFF51AFD7ED558CCDL;
        key ^= key >>> 33;
        key *= 0xC4CEB9FE1A85EC53L;
        return key ^ (key >>> 33);
    }
}
