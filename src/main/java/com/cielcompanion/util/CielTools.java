package com.cielcompanion.util;

/**
 * Small helper class that centralises the model names used by the
 * continuously‑running background monitor and the transliterator.
 * Keeping them in one place makes it easy to change the models
 * without hunting through the code‑base.
 */
public final class CielTools {
    private CielTools() { /* utility class – no instances */ }

    /**
     * Returns the Ollama model tag for the background‑task monitor
     * (HabitTrackerService, SystemMonitor, etc.).
     * Must match the model kept resident by {@code ciel_tools.py}.
     */
    public static String getBackgroundModel() {
        return "qwen3:8b";
    }

    /**
     * Returns the Ollama model tag for the transliteration agent
     * (Katakana conversion, etc.).
     * Must match the model kept resident by {@code ciel_tools.py}.
     */
    public static String getTranslationModel() {
        return "gemma3:12b";
    }
}
