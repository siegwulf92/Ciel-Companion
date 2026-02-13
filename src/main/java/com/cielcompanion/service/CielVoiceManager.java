package com.cielcompanion.service;

import java.util.Properties;
import java.io.InputStream;

/**
 * Handles switching between COMPANION (English translation) and WORLD (Japanese only) modes.
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

    public static String getActiveLanguageCode() {
        return isLanguageLocked() ? "ja-JP" : "en-US";
    }

    public static String getVoiceId() {
        return isLanguageLocked() ? config.getProperty("voice.world.id") : config.getProperty("voice.companion.id");
    }
}