/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.minecraft.input.RemixKeyboardAccess;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes keyboard input through the Remix overlay gate and intercepts the mod's raw shortcuts:
 * Left Alt+X without Control toggles the overlay (Right Alt is AltGr on many layouts, see
 * {@link RemixInputGate#onKey}), and while its capture session is open every key,
 * committed text and IME composition update is withheld from Minecraft. Plain R toggles camera
 * recording once an in-game command has armed it, and = and - scrub time of day while recording.
 *
 * <p>Minecraft 26.3 receives keys as SDL events that {@code SDLEventHandler} queues onto the main
 * thread. {@link KeyEvent#modifiers()} is the SDL modifier mask captured with the event, and key
 * codes are SDL scancodes ({@link KeyEvent#key()}), compared against {@link InputConstants}'
 * {@code KEY_*} values. Matched shortcuts cancel the event so vanilla does not also handle it.
 *
 * <p>A cancelled key event still counts as input for vanilla's AFK frame-rate limit, which
 * {@code keyPress} would otherwise have reset first: an open overlay must not drop to 30 fps after
 * a minute of keyboard use.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardMixin implements RemixKeyboardAccess {
    @Shadow @Final
    private Minecraft minecraft;

    @Shadow
    private long debugCrashKeyTime;

    @Shadow
    private long debugCrashKeyReportedTime;

    @Shadow
    private long debugCrashKeyReportedCount;

    @Shadow
    private boolean usedDebugKeyAsModifier;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onKey(long handle, int action, KeyEvent event, CallbackInfo ci) {
        int key = event.key();
        int mods = event.modifiers();
        // The keyboard-state polls are SDL calls, so they run only for an X press.
        boolean xPress = action == InputConstants.PRESS && key == InputConstants.KEY_X;
        boolean leftAltDown = xPress && InputConstants.isKeyDown(InputConstants.KEY_LALT);
        boolean controlDown = xPress
                && (InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
                        || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL));
        RemixInputGate.KeyDecision decision = RtxRemixMod.getRemixInputGate().onKey(
                key, action, mods, leftAltDown, controlDown);

        if (decision == RemixInputGate.KeyDecision.BLOCK) {
            rtxremix$cancelKey(handle, ci);
            return;
        }
        if (decision == RemixInputGate.KeyDecision.TOGGLE_MENU) {
            rtxremix$cancelKey(handle, ci);
            RtxRemixMod.toggleRemixMenu();
            return;
        }
        if (minecraft.gui.screen() != null
                || (action != InputConstants.PRESS && action != InputConstants.REPEAT)) {
            return;
        }

        if (action == InputConstants.PRESS
                && key == InputConstants.KEY_R
                && RtxRemixMod.shouldHandlePlainPathRecordingShortcut()) {
            rtxremix$cancelKey(handle, ci);
            RtxRemixMod.togglePathRecording();
        } else if (RtxRemixMod.handleCameraTimeShortcut(key, action, mods)) {
            rtxremix$cancelKey(handle, ci);
        }
    }

    /** Cancels a key event after recording it as input, as {@code keyPress} would have. */
    private void rtxremix$cancelKey(long handle, CallbackInfo ci) {
        if (handle != 0L && handle == minecraft.getWindow().handle()) {
            minecraft.getFramerateLimitTracker().onInputReceived();
        }
        ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onChar(long handle, CharacterEvent event, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    /* Committed keyboard or IME text, which vanilla splits into charTyped calls. */
    @Inject(method = "textInput", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onTextInput(long handle, String text, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    /*
     * An IME composition update. A null event only ends a composition, so it still reaches
     * Minecraft: a composition begun in an edit box before capture opened must not stay drawn
     * there after the IME commits or abandons it inside the overlay.
     */
    @Inject(method = "textEditing", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onTextEditing(long handle, PreeditEvent event, CallbackInfo ci) {
        if (event != null && RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Override
    public void rtxremix$resetDebugKeyState() {
        debugCrashKeyTime = -1L;
        debugCrashKeyReportedTime = -1L;
        debugCrashKeyReportedCount = -1L;
        usedDebugKeyAsModifier = false;
    }
}
