/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.nvidia.rtxremix.minecraft.lod.LodSwapTracker.AWAITED;
import static com.nvidia.rtxremix.minecraft.lod.LodSwapTracker.HELD;
import static com.nvidia.rtxremix.minecraft.lod.LodSwapTracker.LINGERING;
import static com.nvidia.rtxremix.minecraft.lod.LodSwapTracker.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodSwapTrackerTest {

    private static final int MAX_LINGER = 30;
    private static final Predicate<LodSection> NOTHING_DRAWN_OVER = section -> false;

    private final LodCapture capture = new LodCapture();
    private final Scene scene = new Scene();
    private final LodSwapTracker tracker = new LodSwapTracker(scene, MAX_LINGER);

    @Test
    void aSectionSplitIntoChildrenStaysUntilAllFourAreReadyThenSwapsInOneFrame() {
        LodSection parent = shownSection(0, 0, 128, 1);
        LodSection[] children = children(parent);

        // The mod draws the children and stops drawing the parent.
        for (LodSection child : children) want(child, 2);
        unwant(parent, 2);
        assertEquals(LINGERING, parent.swap);
        assertTrue(parent.shown, "the parent still covers the ground");

        for (int i = 0; i < 3; i++) install(children[i], 3);
        tracker.resolve(3, false, NOTHING_DRAWN_OVER);
        assertTrue(parent.shown, "one child is still being built");
        for (int i = 0; i < 3; i++) {
            assertEquals(HELD, children[i].swap);
            assertFalse(children[i].shown, "a ready child waits hidden rather than overlapping its parent");
        }

        install(children[3], 4);
        scene.calls.clear();
        tracker.resolve(4, false, NOTHING_DRAWN_OVER);

        assertFalse(parent.hasMesh(), "the parent gives up its mesh");
        assertEquals(NONE, parent.swap);
        for (LodSection child : children) {
            assertTrue(child.shown);
            assertEquals(NONE, child.swap);
        }
        assertEquals(5, scene.calls.size(), "the parent goes and the children appear in the same frame");
        assertEquals("end " + parent, scene.calls.get(0));
        assertEquals(Set.of("show " + children[0], "show " + children[1], "show " + children[2],
                "show " + children[3]), new HashSet<>(scene.calls.subList(1, 5)));
    }

    @Test
    void childrenMergedIntoTheirParentLingerUntilTheParentIsReady() {
        LodSection parent = section(0, 0, 128);
        LodSection[] children = children(parent);
        for (LodSection child : children) show(child, 1);

        want(parent, 2);
        assertEquals(AWAITED, parent.swap);
        for (LodSection child : children) {
            child.wanted = false;
            assertTrue(tracker.close(child, 2), "a shown child lingers when the mod closes it");
        }
        tracker.resolve(2, false, NOTHING_DRAWN_OVER);
        for (LodSection child : children) assertTrue(child.shown);

        install(parent, 3);
        assertEquals(HELD, parent.swap);
        tracker.resolve(3, false, NOTHING_DRAWN_OVER);

        assertTrue(parent.shown);
        for (LodSection child : children) assertFalse(child.hasMesh());
    }

    @Test
    void aSectionTheModStopsDrawingWithoutAReplacementGoesAtOnce() {
        LodSection section = shownSection(0, 0, 64, 1);
        unwant(section, 2);
        assertEquals(LINGERING, section.swap);

        tracker.resolve(2, false, NOTHING_DRAWN_OVER);

        assertFalse(section.hasMesh());
        assertEquals(0, tracker.lingeringCount());
    }

    @Test
    void queuedChangesKeepALingeringSectionUntilTheyAreIn() {
        LodSection section = shownSection(0, 0, 64, 1);
        unwant(section, 2);

        tracker.resolve(2, true, NOTHING_DRAWN_OVER);
        assertTrue(section.shown, "the change showing its replacement may still be queued");

        tracker.resolve(3, false, NOTHING_DRAWN_OVER);
        assertFalse(section.hasMesh());
    }

    @Test
    void aLingeringSectionGivesUpAfterTheMaximum() {
        LodSection parent = shownSection(0, 0, 128, 1);
        LodSection child = section(0, 0, 64);
        want(child, 2);
        unwant(parent, 2);

        tracker.resolve(2 + MAX_LINGER - 1, false, NOTHING_DRAWN_OVER);
        assertTrue(parent.shown);
        tracker.resolve(2 + MAX_LINGER, false, NOTHING_DRAWN_OVER);
        assertFalse(parent.hasMesh(), "a child that never arrives must not keep stale ground forever");
    }

    @Test
    void aLingeringSectionOverMinecraftsTerrainGoesAtOnce() {
        LodSection parent = shownSection(0, 0, 128, 1);
        LodSection child = section(0, 0, 64);
        want(child, 2);
        unwant(parent, 2);

        tracker.resolve(2, false, section -> section == parent);

        assertFalse(parent.hasMesh());
    }

    @Test
    void aLingeringSectionWhoseMeshWasGivenUpEndsAndShowsWhatItHeld() {
        LodSection parent = shownSection(0, 0, 128, 1);
        LodSection[] children = children(parent);
        for (LodSection child : children) want(child, 2);
        unwant(parent, 2);
        install(children[0], 3);
        assertEquals(HELD, children[0].swap);

        // The budget or Minecraft's terrain takes the parent's mesh while it lingers.
        parent.instance = null;
        parent.shown = false;
        tracker.resolve(3, false, NOTHING_DRAWN_OVER);

        assertEquals(NONE, parent.swap, "a section that shows nothing covers nothing");
        assertEquals(0, tracker.lingeringCount());
        assertTrue(children[0].shown, "what it held appears without waiting for the other children");
        assertEquals(AWAITED, children[1].swap);
    }

    @Test
    void aLingeringSectionTheModDrawsAgainStaysAsItIs() {
        LodSection section = shownSection(0, 0, 64, 1);
        LodSection replacement = section(0, 0, 32);
        want(replacement, 2);
        unwant(section, 2);

        want(section, 3);

        assertEquals(NONE, section.swap);
        assertTrue(section.shown);
        tracker.resolve(3, false, NOTHING_DRAWN_OVER);
        assertTrue(section.shown);
    }

    @Test
    void aReadySectionWithNothingToDrawEndsTheWaitOfWhatItReplaces() {
        LodSection parent = shownSection(0, 0, 128, 1);
        LodSection child = section(0, 0, 128);
        want(child, 2);
        unwant(parent, 2);

        // Minecraft's own terrain covers the child: it is ready with no mesh.
        child.ready = true;
        tracker.update(child, 3);
        tracker.resolve(3, false, NOTHING_DRAWN_OVER);

        assertEquals(NONE, child.swap);
        assertFalse(parent.hasMesh());
    }

    @Test
    void aClosedSectionThatWasNotShownLeavesAtOnce() {
        LodSection section = section(0, 0, 64);
        want(section, 1);
        assertEquals(AWAITED, section.swap);

        assertFalse(tracker.close(section, 2));

        assertEquals(NONE, section.swap);
        assertEquals(0, tracker.awaitedCount());
    }

    // --- helpers ---

    private LodSection section(int minX, int minZ, int width) {
        return new LodSection(capture, null, minX, -64, minZ, width);
    }

    private LodSection shownSection(int minX, int minZ, int width, int frame) {
        LodSection section = section(minX, minZ, width);
        show(section, frame);
        return section;
    }

    private void show(LodSection section, int frame) {
        section.wanted = true;
        section.ready = true;
        section.instance = dummyInstance();
        tracker.update(section, frame);
        assertTrue(section.shown);
    }

    private LodSection[] children(LodSection parent) {
        int half = parent.width / 2;
        return new LodSection[] {
                section(parent.minX, parent.minZ, half),
                section(parent.minX + half, parent.minZ, half),
                section(parent.minX, parent.minZ + half, half),
                section(parent.minX + half, parent.minZ + half, half)};
    }

    private void want(LodSection section, int frame) {
        section.wanted = true;
        tracker.update(section, frame);
    }

    private void unwant(LodSection section, int frame) {
        section.wanted = false;
        tracker.update(section, frame);
    }

    /** What the terrain does when a build finishes: the section is ready with a mesh. */
    private void install(LodSection section, int frame) {
        section.ready = true;
        section.instance = dummyInstance();
        section.shown = false;
        tracker.update(section, frame);
    }

    private static RemixInstance dummyInstance() {
        return new RemixInstance(new RemixMesh(new RemixMeshSurface[0]));
    }

    /** Records what the tracker asks for and applies it as the terrain does. */
    private static final class Scene implements LodSwapTracker.Display {
        final List<String> calls = new ArrayList<>();

        @Override
        public void setShown(LodSection section, boolean shown) {
            if (section.instance == null) {
                section.shown = false;
                return;
            }
            if (shown && !section.shown) calls.add("show " + section);
            section.shown = shown;
        }

        @Override
        public void lingerEnded(LodSection section) {
            calls.add("end " + section);
            section.instance = null;
            section.shown = false;
            section.ready = false;
        }
    }
}
