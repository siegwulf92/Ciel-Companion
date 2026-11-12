package com.cielcompanion.service;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HotkeyService implements NativeKeyListener {

    private final VoiceListener voiceListener;
    private int toggleKeyCode;
    private boolean altFallback;
    private boolean ctrlAltFallback;
    private long releaseTimeoutNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1200L);
    private volatile long lastToggleSignalNanos;
    private final java.util.concurrent.atomic.AtomicBoolean keyDown = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService actions = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Ciel-Hotkey-Action");
        thread.setDaemon(true);
        return thread;
    });

    // CORRECTED: Constructor now accepts the VoiceListener
    public HotkeyService(VoiceListener voiceListener) {
        this.voiceListener = voiceListener;
    }

    // CORRECTED: Initialize method no longer takes an argument
    public void initialize() {
        try {
            Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.WARNING);
            logger.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();
            System.out.println("Ciel Debug: JNativeHook registered for global hotkeys.");
        } catch (NativeHookException ex) {
            System.err.println("Ciel Error: There was a problem registering the native hook for hotkeys.");
            System.err.println(ex.getMessage());
            return;
        }

        String keyName = Settings.getHotkeyKey().toUpperCase();
        altFallback = Settings.isHotkeyAltFallbackEnabled();
        ctrlAltFallback = Settings.isHotkeyCtrlAltFallbackEnabled();
        releaseTimeoutNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                Settings.getHotkeyReleaseTimeoutMs());
        try {
            this.toggleKeyCode = NativeKeyEvent.class.getField("VC_" + keyName).getInt(null);
            System.out.println("Ciel Debug: Hotkey for toggle listening set to " + keyName
                    + " (fallbacks: Alt+" + keyName + "=" + altFallback
                    + ", Ctrl+Alt+" + keyName + "=" + ctrlAltFallback
                    + ", release watchdog=" + Settings.getHotkeyReleaseTimeoutMs() + "ms)");
        } catch (Exception e) {
            System.err.println("Ciel Error: Invalid hotkey specified in settings: " + keyName);
            this.toggleKeyCode = NativeKeyEvent.VC_F12; // Default to F12 on error
            System.out.println("Ciel Warning: Defaulting hotkey to F12.");
        }

        GlobalScreen.addNativeKeyListener(this);
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (!isToggleEvent(e)) return;
        long now = System.nanoTime();
        long last = lastToggleSignalNanos;
        // Some laptop Fn layers occasionally lose key-up. Recover only after
        // a quiet interval; OS auto-repeat keeps the interval fresh while a
        // key is physically held, preventing repeated toggles while held.
        if (keyDown.get() && last > 0 && now - last >= releaseTimeoutNanos) {
            keyDown.set(false);
            System.out.println("Ciel Debug: Recovered stale hotkey key-up state after "
                    + Settings.getHotkeyReleaseTimeoutMs() + "ms quiet interval.");
        }
        lastToggleSignalNanos = now;
        if (keyDown.compareAndSet(false, true)) {
            actions.execute(voiceListener::toggleListening);
        }
    }

    private boolean isToggleEvent(NativeKeyEvent event) {
        if (event.getKeyCode() != toggleKeyCode) return false;
        int modifiers = event.getModifiers();
        int relevant = modifiers & (NativeInputEvent.SHIFT_MASK | NativeInputEvent.CTRL_MASK
                | NativeInputEvent.ALT_MASK | NativeInputEvent.META_MASK);
        return relevant == 0
                || (altFallback && relevant == NativeInputEvent.ALT_MASK)
                || (ctrlAltFallback && relevant == (NativeInputEvent.CTRL_MASK | NativeInputEvent.ALT_MASK));
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        if (e.getKeyCode() == toggleKeyCode) keyDown.set(false);
    }

    // CORRECTED: Renamed method for consistency
    public void cleanUp() {
        actions.shutdownNow();
        try {
            if (GlobalScreen.isNativeHookRegistered()) {
                GlobalScreen.removeNativeKeyListener(this);
                GlobalScreen.unregisterNativeHook();
            }
        } catch (NativeHookException ex) {
            System.err.println("Ciel Error: There was a problem unregistering the native hook.");
            System.err.println(ex.getMessage());
        }
    }
}

