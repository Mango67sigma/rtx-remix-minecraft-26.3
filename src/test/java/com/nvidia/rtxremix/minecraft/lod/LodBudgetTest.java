/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodBudgetTest {

    private static final long MIB = 1L << 20;

    @Test
    void theMeshBudgetIsASixteenthOfVideoMemoryWithinItsBounds() {
        assertEquals(512 * MIB, LodTerrain.meshBudgetBytes(8192 * MIB));
        assertEquals(LodTerrain.MIN_MESH_BUDGET_BYTES, LodTerrain.meshBudgetBytes(2048 * MIB));
        assertEquals(LodTerrain.MIN_MESH_BUDGET_BYTES, LodTerrain.meshBudgetBytes(0));
        assertEquals(LodTerrain.MAX_MESH_BUDGET_BYTES, LodTerrain.meshBudgetBytes(48L * 1024 * MIB));
    }

    @Test
    void sectionsArrivingFarthestFirstEndWithTheNearestInstalled() {
        Installed installed = new Installed(new LodBudget(100));
        // The mod sends sections in no particular order; here the worst one.
        assertTrue(installed.offer(400, 40));
        assertTrue(installed.offer(300, 40));
        assertTrue(installed.offer(200, 40), "the farthest gives way to a nearer one");
        assertTrue(installed.offer(100, 40), "and the next farthest to the nearest");

        assertEquals(List.of(100.0, 200.0), installed.distances());
        assertEquals(300.0, installed.budget.radius(), "nothing at or past the nearest given up is built");
        assertFalse(installed.budget.reaches(300));
        assertTrue(installed.budget.reaches(299));
    }

    @Test
    void aSectionFartherThanEveryInstalledOneStarves() {
        Installed installed = new Installed(new LodBudget(100));
        assertTrue(installed.offer(100, 40));
        assertTrue(installed.offer(200, 40));

        assertFalse(installed.offer(300, 40));

        assertEquals(List.of(100.0, 200.0), installed.distances());
        assertEquals(300.0, installed.budget.radius());
        assertFalse(installed.offer(350, 1), "past the radius nothing is admitted, however small");
    }

    @Test
    void givingUpStopsAsSoonAsTheNewMeshFitsWithHeadroom() {
        Installed installed = new Installed(new LodBudget(160));
        assertTrue(installed.offer(100, 40));
        assertTrue(installed.offer(500, 40));
        assertTrue(installed.offer(400, 40));
        assertTrue(installed.offer(300, 40));

        assertTrue(installed.offer(50, 30));

        assertEquals(List.of(50.0, 100.0, 300.0, 400.0), installed.distances(),
                "only the farthest went; the 150 installed bytes leave headroom under 160");
        assertEquals(500.0, installed.budget.radius());
    }

    @Test
    void theRadiusGrowsBackOnceMemoryIsFree() {
        LodBudget budget = new LodBudget(100);
        budget.installed(90);
        assertEquals(-1, budget.admit(500, 40, List.of()));
        assertEquals(500.0, budget.radius());

        assertFalse(budget.relax(), "still full: the radius holds");
        budget.removed(20);
        assertTrue(budget.relax());
        assertEquals(625.0, budget.radius(), 1e-9);
        budget.removed(30);
        assertTrue(budget.relax());
        assertEquals(Double.POSITIVE_INFINITY, budget.radius());
        assertFalse(budget.relax());
    }

    /** Installed sections as the terrain keeps them, with the budget's verdicts applied. */
    private static final class Installed {
        final LodBudget budget;
        final List<LodSection> sections = new ArrayList<>();

        Installed(LodBudget budget) {
            this.budget = budget;
        }

        boolean offer(double distance, long bytes) {
            if (!budget.fits(distance, bytes)) {
                List<LodSection> farthestFirst = new ArrayList<>(sections);
                farthestFirst.sort(Comparator.comparingDouble((LodSection s) -> s.distance).reversed());
                int giveUp = budget.admit(distance, bytes, farthestFirst);
                if (giveUp < 0) return false;
                for (int i = 0; i < giveUp; i++) {
                    LodSection evicted = farthestFirst.get(i);
                    sections.remove(evicted);
                    budget.removed(evicted.meshBytes);
                }
            }
            LodSection section = new LodSection(new LodCapture(), null, 0, 0, 0, 64);
            section.distance = distance;
            section.meshBytes = bytes;
            sections.add(section);
            budget.installed(bytes);
            return true;
        }

        List<Double> distances() {
            List<Double> distances = new ArrayList<>();
            for (LodSection section : sections) distances.add(section.distance);
            distances.sort(Double::compare);
            return distances;
        }
    }
}
