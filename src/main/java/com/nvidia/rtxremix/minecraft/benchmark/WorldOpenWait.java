/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.benchmark;

/**
 * Decides, once per client tick, what a pending benchmark trigger does about the screen that is up
 * while its world opens, whether vanilla QuickPlay or the trigger's own direct load opens it.
 *
 * <p>Vanilla's world-open flow ({@code WorldOpenFlows.openWorld}) takes the save's session lock
 * before it reads the save and keeps it until the world loads or the flow is cancelled, including
 * while one of its own prompts waits for an answer. A second load of the same save meanwhile fails
 * to take the lock, and the screen that failure shows replaces the first flow's prompt, so neither
 * load ever finishes. The trigger therefore loads the world itself only once QuickPlay has done
 * nothing: QuickPlay's own error screen is up, or no world-open screen has been up for
 * {@link #IDLE_LIMIT_TICKS} ticks in a row.
 *
 * <p>The flow's prompts that an unattended run can answer are answered on the tick they are up: a
 * backup prompt proceeds without a backup and without erasing caches, because the run plays a
 * staged copy of its fixture, and the prompt that follows the file fixer's upgrade of that copy
 * joins the world. Every other stop of the flow needs a decision the benchmark must not make for
 * its fixture, such as loading in data pack safe mode, and fails the run.
 *
 * <p>Holds no Minecraft types, so it is tested headless.
 */
final class WorldOpenWait {

    /**
     * Ticks in a row without a world-open screen after which nothing is taken to be opening the
     * world: QuickPlay then gives way to the direct load, and a direct load fails.
     */
    static final int IDLE_LIMIT_TICKS = 20 * 45;

    /** What the screen up while the world opens shows of the world-open flow. */
    enum Stage {
        /** No world-open screen: the title screen, a notice queued before QuickPlay, or none. */
        IDLE,
        /** The game is still starting, or the flow is reading, upgrading or loading the world. */
        LOADING,
        /**
         * The flow asks for a backup before it opens a save from another version, one that needs
         * file fixing among them, or an experimental or old customized world.
         */
        BACKUP_PROMPT,
        /** The flow asks whether to join the world the file fixer has just upgraded. */
        JOIN_PROMPT,
        /** The flow stopped at a screen that needs a decision the benchmark does not make. */
        BLOCKED,
        /** QuickPlay found no world to open. */
        QUICKPLAY_FAILED
    }

    enum Action {
        WAIT,
        /** Answer the backup prompt: proceed without a backup and without erasing caches. */
        PROCEED_WITHOUT_BACKUP,
        /** Answer the join prompt: join the upgraded world. */
        JOIN,
        /** QuickPlay did nothing: load the world directly. */
        LOAD_DIRECTLY,
        /** The world cannot open unattended: stop waiting and name the screen. */
        FAIL
    }

    private int idleTicks;

    /**
     * @param stage      what the screen up this tick shows of the world-open flow
     * @param directLoad whether the trigger has requested its own load, instead of waiting on QuickPlay
     * @return what the trigger does this tick
     */
    Action onTick(Stage stage, boolean directLoad) {
        idleTicks = stage == Stage.IDLE ? idleTicks + 1 : 0;
        return switch (stage) {
            case LOADING -> Action.WAIT;
            case BACKUP_PROMPT -> Action.PROCEED_WITHOUT_BACKUP;
            case JOIN_PROMPT -> Action.JOIN;
            case BLOCKED -> Action.FAIL;
            case QUICKPLAY_FAILED -> directLoad ? Action.FAIL : Action.LOAD_DIRECTLY;
            case IDLE -> idleTicks < IDLE_LIMIT_TICKS
                    ? Action.WAIT
                    : directLoad ? Action.FAIL : Action.LOAD_DIRECTLY;
        };
    }

    /** Ticks in a row, up to and including the last one, without a world-open screen. */
    int idleTicks() {
        return idleTicks;
    }

    /** Starts the idle count over, for a new trigger or a newly requested load. */
    void reset() {
        idleTicks = 0;
    }
}
