/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The release of a compile task's region goes to exactly one of its build and its cancel. */
class CompileTaskRegionOwnerTest {

    @Test
    void aTaskThatRunsKeepsItsRegionFromALaterCancel() {
        CompileTaskRegionOwner owner = new CompileTaskRegionOwner();
        assertTrue(owner.claimForRun());
        assertFalse(owner.claimForCancel(), "a cancel after the build started must not release");
        assertFalse(owner.claimForRun(), "a second run never takes the region again");
    }

    @Test
    void aTaskCancelledBeforeItRunsReleasesOnceInTheCancel() {
        CompileTaskRegionOwner owner = new CompileTaskRegionOwner();
        assertTrue(owner.claimForCancel());
        assertFalse(owner.claimForCancel(), "cancelling twice releases once");
        assertFalse(owner.claimForRun(), "a run after the cancel must not read the released region");
    }

    @Test
    void underARaceExactlyOneSideOwnsTheRelease() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 2_000; round++) {
                CompileTaskRegionOwner owner = new CompileTaskRegionOwner();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Boolean> run = pool.submit(() -> {
                    start.await();
                    return owner.claimForRun();
                });
                Future<Boolean> cancel = pool.submit(() -> {
                    start.await();
                    return owner.claimForCancel();
                });
                int owners = (run.get() ? 1 : 0) + (cancel.get() ? 1 : 0);
                assertEquals(1, owners, "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
