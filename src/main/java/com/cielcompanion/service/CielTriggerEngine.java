package com.cielcompanion.service;

import com.cielcompanion.CielState;

/**
 * Handles the "World Voice" behavior and Puzzle States.
 */
public class CielTriggerEngine {

    private static boolean isTensuraPuzzleActive = false;
    private static long puzzleRequestTime = 0;
    private static final long PUZZLE_TIMEOUT_MS = 15000; // 15 seconds to answer

    public enum Urgency {
        NORMAL,
        URGENT,   // 緊急 (Kinkyuu)
        CRITICAL  // 告 (Koku)
    }

    public static void announce(String message, Urgency urgency) {
        String prefix = switch (urgency) {
            case CRITICAL -> "告。";
            case URGENT -> "緊急。";
            default -> "";
        };

        CielState.getEmotionManager().ifPresent(em -> {
            if (urgency == Urgency.CRITICAL) em.triggerEmotion("Focused", 1.0, "Announcement");
            if (urgency == Urgency.URGENT) em.triggerEmotion("Excited", 0.7, "Danger");
        });

        SpeechService.speakPreformatted(prefix + " " + message);
    }

    public static void onEnterFmaWorld() {
        announce("Equivalent Exchange law detected. Magic components are now volatile and will be consumed.", Urgency.URGENT);
    }

    public static void onEnterTensuraWorld() {
        isTensuraPuzzleActive = true;
        CielVoiceManager.setState(CielVoiceManager.VoiceState.WORLD_VOICE);
        
        // Initial "Glitch" announcement (She speaks Japanese now)
        // "Notice. Unexpected transfer detected. Language matrix unstable."
        announce("Unexpected transfer detected. Language matrix unstable. Requesting immediate analysis.", Urgency.CRITICAL);
    }

    /**
     * DM triggers this when the moment is right for Ciel to ask for permission.
     */
    public static void promptForRaphaelCopy() {
        if (!isTensuraPuzzleActive) return;
        
        puzzleRequestTime = System.currentTimeMillis();
        // "Notice. Unique Skill 'Raphael' missing from subject Rimuru Tempest. Permission to synthesize duplicate?"
        // Since we are in WORLD_VOICE mode, this text will be auto-converted to Katakana-English
        // or you can hardcode the Japanese string here if you prefer.
        announce("Unique Skill Raphael missing from subject Rimuru Tempest. Permission to synthesize duplicate?", Urgency.CRITICAL);
    }

    /**
     * Called when players say "Copy/Confirm/Yes"
     */
    public static void attemptPuzzleSolution() {
        if (!isTensuraPuzzleActive) return;

        // Check if within the 15-second window (optional difficulty)
        // You can remove this time check if you want it to be more forgiving.
        if (System.currentTimeMillis() - puzzleRequestTime < PUZZLE_TIMEOUT_MS) {
            solveTensuraPuzzle();
        } else {
            // Optional: If they missed the window, she repeats it or stays silent.
            // For now, let's allow it to work anytime after the prompt to be safe.
            solveTensuraPuzzle();
        }
    }

    private static void solveTensuraPuzzle() {
        isTensuraPuzzleActive = false;
        CielVoiceManager.setState(CielVoiceManager.VoiceState.COMPANION); // Restore English
        
        // Success message in English
        announce("Acknowledged. Unique Skill Raphael synthesis complete. Connection to individual Rimuru Tempest re-established. Universal translation matrix restored.", Urgency.NORMAL);
    }
}