/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.benchmark;

import com.nvidia.rtxremix.minecraft.benchmark.WorldOpenWait.Action;
import com.nvidia.rtxremix.minecraft.benchmark.WorldOpenWait.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldOpenWaitTest {
    private static final boolean QUICKPLAY = false;
    private static final boolean DIRECT_LOAD = true;
    private static final int LIMIT = WorldOpenWait.IDLE_LIMIT_TICKS;

    @Test
    void backupPromptIsAnsweredOnTheTickItIsUp() {
        assertEquals(Action.PROCEED_WITHOUT_BACKUP, new WorldOpenWait().onTick(Stage.BACKUP_PROMPT, QUICKPLAY));
        assertEquals(Action.PROCEED_WITHOUT_BACKUP, new WorldOpenWait().onTick(Stage.BACKUP_PROMPT, DIRECT_LOAD));
    }

    @Test
    void joinPromptAfterTheFileFixerJoinsTheWorld() {
        assertEquals(Action.JOIN, new WorldOpenWait().onTick(Stage.JOIN_PROMPT, QUICKPLAY));
        assertEquals(Action.JOIN, new WorldOpenWait().onTick(Stage.JOIN_PROMPT, DIRECT_LOAD));
    }

    /** QuickPlay's flow holds the save's session lock for as long as any of its screens is up. */
    @Test
    void quickPlayIsNeverRacedWhileItsFlowShowsAScreen() {
        WorldOpenWait wait = new WorldOpenWait();

        for (int tick = 0; tick < 2 * LIMIT; tick++) {
            assertEquals(Action.WAIT, wait.onTick(Stage.LOADING, QUICKPLAY));
        }
        assertEquals(0, wait.idleTicks());
    }

    @Test
    void blockedFlowFailsInsteadOfFallingBack() {
        assertEquals(Action.FAIL, new WorldOpenWait().onTick(Stage.BLOCKED, QUICKPLAY));
        assertEquals(Action.FAIL, new WorldOpenWait().onTick(Stage.BLOCKED, DIRECT_LOAD));
    }

    @Test
    void quickPlayThatFoundNoWorldFallsBackAtOnce() {
        assertEquals(Action.LOAD_DIRECTLY, new WorldOpenWait().onTick(Stage.QUICKPLAY_FAILED, QUICKPLAY));
    }

    @Test
    void idleQuickPlayFallsBackAtTheIdleLimit() {
        WorldOpenWait wait = new WorldOpenWait();

        idleFor(wait, LIMIT - 1, QUICKPLAY);

        assertEquals(Action.LOAD_DIRECTLY, wait.onTick(Stage.IDLE, QUICKPLAY));
        assertEquals(LIMIT, wait.idleTicks());
    }

    @Test
    void anyWorldOpenScreenStartsTheIdleCountOver() {
        WorldOpenWait wait = new WorldOpenWait();

        idleFor(wait, LIMIT - 1, QUICKPLAY);
        assertEquals(Action.WAIT, wait.onTick(Stage.LOADING, QUICKPLAY));
        idleFor(wait, LIMIT - 1, QUICKPLAY);

        assertEquals(Action.LOAD_DIRECTLY, wait.onTick(Stage.IDLE, QUICKPLAY));
    }

    @Test
    void directLoadThatOpensNothingFailsAtTheIdleLimit() {
        WorldOpenWait wait = new WorldOpenWait();

        idleFor(wait, LIMIT - 1, DIRECT_LOAD);

        assertEquals(Action.FAIL, wait.onTick(Stage.IDLE, DIRECT_LOAD));
    }

    @Test
    void resetGivesANewLoadTheWholeIdleLimit() {
        WorldOpenWait wait = new WorldOpenWait();
        idleFor(wait, LIMIT - 1, QUICKPLAY);

        wait.reset();

        idleFor(wait, LIMIT - 1, DIRECT_LOAD);
        assertEquals(Action.FAIL, wait.onTick(Stage.IDLE, DIRECT_LOAD));
    }

    /**
     * Minecraft 26.3 opening a fixture saved by 1.21.1: the game starts, QuickPlay reads the save,
     * asks for a backup because the save needs file fixing, upgrades it, asks to join, and loads it.
     * An answered prompt stays up until its answer runs, which the controller reads as loading.
     */
    @Test
    void olderFixtureOpensThroughQuickPlayWithoutAFallback() {
        WorldOpenWait wait = new WorldOpenWait();
        List<Action> answers = new ArrayList<>();

        List<Stage> flow = new ArrayList<>();
        flow.addAll(repeat(Stage.LOADING, 100));
        flow.add(Stage.BACKUP_PROMPT);
        flow.addAll(repeat(Stage.LOADING, 2 * LIMIT));
        flow.add(Stage.JOIN_PROMPT);
        flow.addAll(repeat(Stage.LOADING, 2 * LIMIT));
        for (Stage stage : flow) {
            Action action = wait.onTick(stage, QUICKPLAY);
            if (action != Action.WAIT) {
                answers.add(action);
            }
        }

        assertEquals(List.of(Action.PROCEED_WITHOUT_BACKUP, Action.JOIN), answers);
    }

    private static void idleFor(WorldOpenWait wait, int ticks, boolean directLoad) {
        for (int tick = 0; tick < ticks; tick++) {
            assertEquals(Action.WAIT, wait.onTick(Stage.IDLE, directLoad));
        }
    }

    private static List<Stage> repeat(Stage stage, int ticks) {
        return Collections.nCopies(ticks, stage);
    }
}
