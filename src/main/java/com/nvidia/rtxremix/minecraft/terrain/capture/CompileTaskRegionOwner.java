/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides who releases a compile task's region: the build that runs the task, or the
 * {@code cancel} that stops it before it runs. Exactly one claim succeeds per task.
 *
 * <p>The protocol relies on the order vanilla gives both sides. {@code cancel} sets the task's
 * {@code isCancelled} flag before it calls {@link #claimForCancel()}; {@code doTask} calls
 * {@link #claimForRun()} at its HEAD, before its first statement reads {@code isCancelled}. A
 * run claim that fails therefore happens after a successful cancel claim, which happens after
 * the flag was set, so the task sees itself cancelled and returns without reading the region
 * the cancel released.
 */
public final class CompileTaskRegionOwner {
    private static final int PENDING = 0;
    private static final int RUNNING = 1;
    private static final int CANCELLED = 2;

    private final AtomicInteger state = new AtomicInteger(PENDING);

    /** At {@code doTask} HEAD: true when the running build owns the region's release. */
    public boolean claimForRun() {
        return state.compareAndSet(PENDING, RUNNING);
    }

    /** After {@code cancel} set the flag: true when the canceller must release the region now. */
    public boolean claimForCancel() {
        return state.compareAndSet(PENDING, CANCELLED);
    }
}
