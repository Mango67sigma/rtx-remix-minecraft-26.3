/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Sections waiting for a mesh build, nearest the camera first, except that an urgent one,
 * which ends a detail swap or corrects a clip, goes before any that is not.
 *
 * <p>The order is kept sorted only when it is read after a change or a camera move, with
 * the next section at the end of the list so that taking it costs nothing. A section that
 * leaves the queue early is only unmarked and skipped when its turn comes. A section taken
 * keeps its urgency for the caller to act on. Render thread only.
 */
final class LodBuildQueue {

    /** Sorts the next section last: urgent after not urgent, nearer after farther. */
    private static final Comparator<LodSection> NEXT_LAST = (a, b) -> {
        if (a.urgent != b.urgent) return a.urgent ? 1 : -1;
        return Double.compare(b.distance, a.distance);
    };

    private final ArrayList<LodSection> sections = new ArrayList<>();
    private boolean sorted = true;

    /** Queues a section, or raises the urgency of one already queued. */
    void add(LodSection section, boolean urgent) {
        if (urgent && !section.urgent) {
            section.urgent = true;
            sorted = false;
        }
        if (section.buildQueued) return;
        section.buildQueued = true;
        sections.add(section);
        sorted = false;
    }

    /** Takes a section out of the queue; its entry is skipped when it comes up. */
    static void remove(LodSection section) {
        section.buildQueued = false;
        section.urgent = false;
    }

    /** The camera moved, so the distances are to be measured again. */
    void reorder() {
        sorted = false;
    }

    /** The next section to build, or null when none waits. */
    LodSection poll(double cameraX, double cameraZ) {
        if (!sorted) {
            for (int i = 0, count = sections.size(); i < count; i++) {
                LodSection section = sections.get(i);
                section.distance = section.distanceTo(cameraX, cameraZ);
            }
            sections.sort(NEXT_LAST);
            sorted = true;
        }
        while (!sections.isEmpty()) {
            LodSection section = sections.remove(sections.size() - 1);
            if (!section.buildQueued) continue;
            section.buildQueued = false;
            return section;
        }
        return null;
    }

    /** Entries in the list, skipped ones included until they come up. */
    int size() {
        return sections.size();
    }

    void clear() {
        for (int i = 0, count = sections.size(); i < count; i++) remove(sections.get(i));
        sections.clear();
        sorted = true;
    }
}
