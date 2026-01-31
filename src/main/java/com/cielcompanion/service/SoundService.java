package com.cielcompanion.service;

import javax.sound.sampled.*;

/**
 * A new service to handle playing programmatic sound effects for D&D mode.
 * Note: This generates simple tones as placeholders for actual sound files.
 */
public class SoundService {

    /**
     * Initializes the SoundService. This method is currently empty but is required
     * to ensure the service is correctly loaded by the main application.
     */
    public void initialize() {
        // This space is reserved for any future setup, like loading sound files.
        System.out.println("Ciel Debug: SoundService initialized.");
    }

    public void playSound(String soundName) {
        new Thread(() -> {
            try {
                String lowerSoundName = soundName.toLowerCase();
                if (lowerSoundName.contains("battle") || lowerSoundName.contains("combat")) {
                    playBattleSound();
                } else if (lowerSoundName.contains("forest")) {
                    playForestSound();
                } else if (lowerSoundName.contains("wolf")) {
                    playWolfHowl();
                } else if (lowerSoundName.contains("bear")) {
                    playBearGrowl();
                } else {
                    System.err.println("Ciel Warning: Unknown sound requested: " + soundName);
                }
            } catch (Exception e) {
                System.err.println("Ciel Error: Could not play sound effect.");
                e.printStackTrace();
            }
        }).start();
    }

    private void playTone(int frequency, int durationMs) throws LineUnavailableException {
        AudioFormat af = new AudioFormat(44100, 8, 1, true, false);
        SourceDataLine sdl = AudioSystem.getSourceDataLine(af);
        sdl.open(af);
        sdl.start();
        byte[] buf = new byte[1];
        for (int i = 0; i < durationMs * 44.1; i++) {
            double angle = i / (44100.0 / frequency) * 2.0 * Math.PI;
            buf[0] = (byte) (Math.sin(angle) * 100);
            sdl.write(buf, 0, 1);
        }
        sdl.drain();
        sdl.stop();
        sdl.close();
    }

    private void playBattleSound() throws LineUnavailableException {
        playTone(200, 150);
        playTone(150, 150);
        playTone(200, 150);
        playTone(150, 150);
    }

    private void playForestSound() throws LineUnavailableException {
        playTone(800, 200);
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        playTone(900, 150);
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        playTone(850, 180);
    }

    private void playWolfHowl() throws LineUnavailableException {
        playTone(400, 500);
        playTone(600, 800);
        playTone(400, 500);
    }
    
    private void playBearGrowl() throws LineUnavailableException {
        playTone(100, 800);
        playTone(80, 600);
    }
}
