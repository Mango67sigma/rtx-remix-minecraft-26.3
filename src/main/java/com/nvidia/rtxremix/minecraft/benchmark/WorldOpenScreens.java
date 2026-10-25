/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.benchmark;

import com.nvidia.rtxremix.minecraft.mixin.BackupConfirmScreenAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ConfirmScreenAccessor;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DatapackLoadFailureScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.FileFixerAbortedScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.GenericWaitingScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.NoticeWithLinkScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.RecoverWorldDataScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.FileFixerProgressScreen;
import net.minecraft.client.quickplay.QuickPlay;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

/**
 * Reads the screens of vanilla's single-player world-open flow, which QuickPlay and the
 * benchmark's direct load both run ({@code WorldOpenFlows.openWorld} and the steps it chains), and
 * gives the two answers an unattended benchmark gives to its prompts. {@link WorldOpenWait} decides
 * which screen gets which answer.
 */
final class WorldOpenScreens {

    /** Title of the confirmation the flow shows once the file fixer has upgraded a world. */
    private static final String UPGRADE_DONE_TITLE = "upgradeWorld.done";

    private WorldOpenScreens() {
    }

    /**
     * @param screen the screen up while the benchmark world opens
     * @return what that screen shows of the world-open flow
     */
    static WorldOpenWait.Stage stage(@Nullable Screen screen) {
        if (screen == null) {
            return WorldOpenWait.Stage.IDLE;
        }
        if (screen instanceof BackupConfirmScreen) {
            return WorldOpenWait.Stage.BACKUP_PROMPT;
        }
        // The flow's own confirmations are plain ConfirmScreens; subclasses such as the ban
        // notices queued before QuickPlay belong to other flows.
        if (screen.getClass() == ConfirmScreen.class) {
            return UPGRADE_DONE_TITLE.equals(titleKey(screen))
                    ? WorldOpenWait.Stage.JOIN_PROMPT
                    : WorldOpenWait.Stage.BLOCKED;
        }
        if (screen instanceof DisconnectedScreen) {
            return QuickPlay.ERROR_TITLE.equals(screen.getTitle())
                    ? WorldOpenWait.Stage.QUICKPLAY_FAILED
                    : WorldOpenWait.Stage.BLOCKED;
        }
        if (screen instanceof GenericMessageScreen
                || screen instanceof GenericWaitingScreen
                || screen instanceof FileFixerProgressScreen
                || screen instanceof ProgressScreen
                || screen instanceof LevelLoadingScreen) {
            return WorldOpenWait.Stage.LOADING;
        }
        if (screen instanceof AlertScreen
                || screen instanceof DatapackLoadFailureScreen
                || screen instanceof RecoverWorldDataScreen
                || screen instanceof FileFixerAbortedScreen
                || screen instanceof NoticeWithLinkScreen) {
            return WorldOpenWait.Stage.BLOCKED;
        }
        return WorldOpenWait.Stage.IDLE;
    }

    /** Proceeds past a backup prompt without a backup and without erasing caches. */
    static void proceedWithoutBackup(BackupConfirmScreen screen) {
        ((BackupConfirmScreenAccessor) screen).rtxremix$onProceed().proceed(false, false);
    }

    /** Joins the world the file fixer has just upgraded. */
    static void joinUpgradedWorld(ConfirmScreen screen) {
        ((ConfirmScreenAccessor) screen).rtxremix$callback().accept(true);
    }

    /** The screen's class and, when it has one, the translation key of its title, for a log line. */
    static String describe(@Nullable Screen screen) {
        if (screen == null) {
            return "none";
        }
        String key = titleKey(screen);
        String name = screen.getClass().getName();
        return key.isEmpty() ? name : name + " (" + key + ")";
    }

    private static String titleKey(Screen screen) {
        return screen.getTitle().getContents() instanceof TranslatableContents translatable
                ? translatable.getKey()
                : "";
    }
}
