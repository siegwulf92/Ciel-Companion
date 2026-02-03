package com.cielcompanion.service;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Handles playback of sound effects from the Campaign SFX folder.
 * Supports .wav files natively.
 */
public class SoundService {

    private Path sfxPath;

    public void initialize() {
        String campaignPath = Settings.getDndCampaignPath();
        if (campaignPath != null && !campaignPath.isBlank()) {
            this.sfxPath = Paths.get(campaignPath, "SFX");
            if (!Files.exists(this.sfxPath)) {
                try {
                    Files.createDirectories(this.sfxPath);
                    System.out.println("Ciel Debug (Sound): Created SFX directory at " + this.sfxPath);
                } catch (IOException e) {
                    System.err.println("Ciel Error (Sound): Could not create SFX directory.");
                }
            } else {
                System.out.println("Ciel Debug (Sound): SFX directory found at " + this.sfxPath);
            }
        } else {
            System.out.println("Ciel Warning (Sound): Campaign path not set. Sound effects disabled.");
        }
    }

    public void playSound(String soundName) {
        if (sfxPath == null || !Files.exists(sfxPath)) {
            System.err.println("Ciel Error (Sound): SFX Path unavailable.");
            return;
        }

        new Thread(() -> {
            try {
                Optional<Path> fileOpt = findSoundFile(soundName);
                if (fileOpt.isPresent()) {
                    playWav(fileOpt.get().toFile());
                } else {
                    System.out.println("Ciel Warning (Sound): No sound file found for keyword '" + soundName + "'");
                }
            } catch (Exception e) {
                System.err.println("Ciel Error (Sound): Sound playback failed.");
                e.printStackTrace();
            }
        }).start();
    }

    private Optional<Path> findSoundFile(String keyword) {
        try (Stream<Path> files = Files.walk(sfxPath)) {
            return files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".wav"))
                .filter(p -> p.getFileName().toString().toLowerCase().contains(keyword.toLowerCase()))
                .findFirst();
        } catch (IOException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    private void playWav(File file) {
        try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(file)) {
            DataLine.Info info = new DataLine.Info(Clip.class, audioStream.getFormat());
            Clip clip = (Clip) AudioSystem.getLine(info);
            
            clip.open(audioStream);
            clip.start();
            
            System.out.println("Ciel Debug (Sound): Playing SFX " + file.getName());
            
            // Wait for playback to complete
            while (!clip.isRunning()) Thread.sleep(10);
            while (clip.isRunning()) Thread.sleep(10);
            
            clip.close();
        } catch (Exception e) {
            System.err.println("Ciel Error (Sound): Failed to play WAV file " + file.getName() + ". (Format might be unsupported)");
            e.printStackTrace();
        }
    }
}