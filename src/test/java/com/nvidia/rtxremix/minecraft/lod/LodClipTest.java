/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodClipTest {

    /** Chunks 4..7 by -4..-1: a 64-block section at block (64, -64). */
    private static final LodSection SECTION = new LodSection(new LodCapture(), null, 64, -64, -64, 64);

    @Test
    void aSectionNoDrawnColumnTouchesHasSignatureZero() {
        LodClip clip = clipOf(0, 0, 3, -1, 8, -2, 5, 0);
        assertEquals(0, clip.signature(SECTION));
        assertFalse(clip.covers(SECTION));
        assertEquals(0, LodClip.NONE.signature(SECTION));
    }

    @Test
    void equalClippedChunksGiveEqualSignaturesWhateverLiesOutside() {
        LodClip first = clipOf(5, -2, 6, -3);
        LodClip second = clipOf(5, -2, 6, -3, 0, 0, 9, 9);
        assertNotEquals(0, first.signature(SECTION));
        assertEquals(first.signature(SECTION), second.signature(SECTION));
    }

    @Test
    void theSignatureChangesWithTheChunksTheClipRemoves() {
        long one = clipOf(5, -2).signature(SECTION);
        long other = clipOf(6, -2).signature(SECTION);
        long both = clipOf(5, -2, 6, -2).signature(SECTION);
        assertNotEquals(one, other);
        assertNotEquals(one, both);
        assertNotEquals(other, both);
    }

    @Test
    void itCoversASectionOnlyWhenEveryChunkOfItIsDrawn() {
        Set<Long> all = new HashSet<>();
        for (int x = 4; x <= 7; x++) {
            for (int z = -4; z <= -1; z++) all.add(key(x, z));
        }
        LodClip whole = LodClip.around(0, 0, 8, (x, z) -> all.contains(key(x, z)));
        assertTrue(whole.covers(SECTION));

        all.remove(key(7, -4));
        LodClip holed = LodClip.around(0, 0, 8, (x, z) -> all.contains(key(x, z)));
        assertFalse(holed.covers(SECTION));
        assertNotEquals(0, holed.signature(SECTION));
    }

    @Test
    void nothingBeyondTheWindowIsClipped() {
        LodClip clip = LodClip.around(0, 0, 2, (x, z) -> true);
        assertTrue(clip.clips(2, -2));
        assertFalse(clip.clips(3, 0));
        assertFalse(clip.clips(0, -3));
        assertFalse(clip.covers(SECTION), "a section past the window's edge is never wholly covered");
        assertTrue(clip.clipsAny(-10, 10, -10, 10));
        assertFalse(clip.clipsAny(3, 10, 0, 0));
    }

    private static LodClip clipOf(int... chunks) {
        Set<Long> drawn = new HashSet<>();
        for (int i = 0; i < chunks.length; i += 2) drawn.add(key(chunks[i], chunks[i + 1]));
        return LodClip.around(0, 0, 10, (x, z) -> drawn.contains(key(x, z)));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
