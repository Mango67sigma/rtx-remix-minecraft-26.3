/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Keeps the ground covered while the LOD mod swaps sections of one detail for those of
 * another.
 *
 * <p>The mod swaps only once the sections replacing a region are uploaded, but a Remix mesh
 * is built later, on a builder thread. So a section the mod stops drawing lingers, still
 * shown, while a section it now draws over the same ground is awaited, and a section
 * whose mesh is ready is held hidden while a section it overlaps lingers. When the last
 * awaited section is installed, the lingering ones go and the held ones appear in the same
 * frame: the ground changes detail at once rather than through a hole or a frame of both.
 * A section lingers for at most a configured number of frames, and gives up at once when
 * Minecraft's own terrain now covers part of it.
 *
 * <p>The tracker decides; its {@link Display} shows, hides and drops meshes. Render thread
 * only. The linger, await and hold states follow {@code LodTerrain} in
 * kazumasa200/rtx-remix-minecraft-26-3 (Apache-2.0).
 */
final class LodSwapTracker {

    static final byte NONE = 0;
    /** Drawn by the mod, its mesh not yet installed. */
    static final byte AWAITED = 1;
    /** No longer drawn by the mod, or closed, and still shown while a section over its ground is awaited. */
    static final byte LINGERING = 2;
    /** Drawn by the mod and installed, held hidden while a section it overlaps lingers. */
    static final byte HELD = 3;

    /** What the tracker asks of the scene. */
    interface Display {
        /** Shows or hides the section's installed mesh; a section without one stays hidden. */
        void setShown(LodSection section, boolean shown);

        /** A lingering section is no longer needed on screen. */
        void lingerEnded(LodSection section);
    }

    private final Display display;
    private final int maxLingerFrames;
    private final List<LodSection> awaited = new ArrayList<>();
    private final List<LodSection> lingering = new ArrayList<>();
    private final List<LodSection> held = new ArrayList<>();

    LodSwapTracker(Display display, int maxLingerFrames) {
        this.display = display;
        this.maxLingerFrames = maxLingerFrames;
    }

    /**
     * Places a mirrored section after its {@code wanted}, {@code ready} or mesh changed, and
     * shows or hides its mesh to match.
     */
    void update(LodSection section, int frame) {
        if (section.wanted) {
            if (section.swap == LINGERING) setSwap(section, NONE, frame);
            if (!section.hasMesh()) {
                // Nothing to draw: either a mesh is coming, or none is needed.
                setSwap(section, section.ready ? NONE : AWAITED, frame);
                display.setShown(section, false);
            } else if (overlapsAny(section, lingering)) {
                setSwap(section, HELD, frame);
                display.setShown(section, false);
            } else {
                setSwap(section, NONE, frame);
                display.setShown(section, true);
            }
        } else if (section.swap == LINGERING) {
            display.setShown(section, true);
        } else if (section.shown) {
            setSwap(section, LINGERING, frame);
        } else {
            setSwap(section, NONE, frame);
        }
    }

    /**
     * The mod closed a section. A shown one lingers like one the mod stopped drawing;
     * any other leaves the swap at once.
     *
     * @return whether the section lingers, keeping its mesh until {@link Display#lingerEnded}
     */
    boolean close(LodSection section, int frame) {
        if (section.shown) {
            if (section.swap != LINGERING) setSwap(section, LINGERING, frame);
            return true;
        }
        setSwap(section, NONE, frame);
        return false;
    }

    /**
     * Ends the swaps that are complete. A lingering section goes once no awaited section
     * overlaps it, unless changes are still queued, which may hold the section replacing
     * it; or once it has lingered too long, Minecraft's terrain covers part of it, or its
     * mesh was given up. Then every held section that no longer overlaps a lingering one
     * is shown.
     *
     * @param changesPending whether the mod's changes were left queued for the next frame
     * @param overTerrain    whether a section's mesh lies over terrain Minecraft now draws
     */
    void resolve(int frame, boolean changesPending, Predicate<LodSection> overTerrain) {
        // Backwards: ending one moves the last of its list into its place.
        for (int i = lingering.size() - 1; i >= 0; i--) {
            LodSection section = lingering.get(i);
            // One whose mesh was given up covers nothing and holds back nothing.
            boolean waits = section.shown
                    && frame - section.lingerSince < maxLingerFrames
                    && !overTerrain.test(section)
                    && (changesPending || overlapsAny(section, awaited));
            if (waits) continue;
            setSwap(section, NONE, frame);
            display.lingerEnded(section);
        }
        for (int i = held.size() - 1; i >= 0; i--) {
            LodSection section = held.get(i);
            if (overlapsAny(section, lingering)) continue;
            setSwap(section, NONE, frame);
            display.setShown(section, section.wanted);
        }
    }

    /** Whether a lingering section overlaps the given one, so building it ends a swap. */
    boolean overlapsLingering(LodSection section) {
        return overlapsAny(section, lingering);
    }

    /** Takes a section out of the swap without asking anything of the display. */
    void forget(LodSection section) {
        setSwap(section, NONE, 0);
    }

    /** The sections lingering now; the list is live. */
    List<LodSection> lingering() {
        return lingering;
    }

    /** The sections awaited now; the list is live. */
    List<LodSection> awaited() {
        return awaited;
    }

    int awaitedCount() {
        return awaited.size();
    }

    int lingeringCount() {
        return lingering.size();
    }

    int heldCount() {
        return held.size();
    }

    /** Ends every swap without asking anything of the display. */
    void clear() {
        while (!awaited.isEmpty()) forget(awaited.get(awaited.size() - 1));
        while (!lingering.isEmpty()) forget(lingering.get(lingering.size() - 1));
        while (!held.isEmpty()) forget(held.get(held.size() - 1));
    }

    private void setSwap(LodSection section, byte state, int frame) {
        if (section.swap == state) return;
        List<LodSection> from = listOf(section.swap);
        if (from != null) {
            LodSection last = from.remove(from.size() - 1);
            if (last != section) {
                from.set(section.swapIndex, last);
                last.swapIndex = section.swapIndex;
            }
            section.swapIndex = -1;
        }
        List<LodSection> to = listOf(state);
        if (to != null) {
            section.swapIndex = to.size();
            to.add(section);
        }
        section.swap = state;
        if (state == LINGERING) section.lingerSince = frame;
    }

    private List<LodSection> listOf(byte state) {
        return switch (state) {
            case AWAITED -> awaited;
            case LINGERING -> lingering;
            case HELD -> held;
            default -> null;
        };
    }

    private static boolean overlapsAny(LodSection section, List<LodSection> others) {
        for (int i = 0, count = others.size(); i < count; i++) {
            LodSection other = others.get(i);
            if (other != section && section.overlaps(other)) return true;
        }
        return false;
    }
}
