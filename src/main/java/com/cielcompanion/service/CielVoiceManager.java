package com.cielcompanion.service;

import com.cielcompanion.CielState;
import java.util.Properties;
import java.io.InputStream;

/**
 * Handles switching between COMPANION (English/Japanese hybrid) and WORLD (Japanese only) modes.
 */
public class CielVoiceManager {

    public enum VoiceState {
        COMPANION, 
        WORLD_VOICE
    }

    private static VoiceState currentState = VoiceState.COMPANION;
    private static Properties config = new Properties();

    static {
        try (InputStream is = CielVoiceManager.class.getResourceAsStream("/Ciel_Config.properties")) {
            if (is != null) config.load(is);
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to load Ciel_Config.properties");
        }
    }

    public static void setState(VoiceState state) {
        currentState = state;
        System.out.println("Ciel Debug: Voice state shifted to " + state);
    }

    public static VoiceState getCurrentState() {
        return currentState;
    }

    public static boolean isLanguageLocked() {
        return currentState == VoiceState.WORLD_VOICE;
    }

    /**
     * Determines the language Ciel should speak based on Mode and Lock state.
     */
    public static String getActiveLanguageCode() {
        // 1. If Tensura Puzzle is active, FORCE Japanese.
        if (isLanguageLocked()) {
            return "ja-JP";
        }
        
        // 2. If in D&D Mode (and not locked), speak English.
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            return "en-US";
        }

        // 3. Default App Mode is Japanese (as per user preference).
        return "ja-JP";
    }

    /**
     * Returns the specific Neural Voice ID based on the active language.
     */
    public static String getActiveVoiceName() {
        String lang = getActiveLanguageCode();
        if ("en-US".equals(lang)) {
            return config.getProperty("voice.english.id", "en-US-JennyNeural");
        } else {
            return config.getProperty("voice.japanese.id", "ja-JP-NanamiNeural");
        }
    }
}