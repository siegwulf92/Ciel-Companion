package com.cielcompanion.service;

import com.cielcompanion.dnd.DndCampaignService;
import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Stream;

public class SoundService {

    private Path sfxPath;
    private Path worldsPath; // Added to track the root Worlds folder
    private Clip ambientClip;
    private Clip battleClip;
    private long ambientPausePosition = 0;
    
    // We need access to the campaign service to know the current world
    private static DndCampaignService campaignServiceRef; 

    public void initialize() {
        String campaignPath = Settings.getDndCampaignPath();
        if (campaignPath != null && !campaignPath.isBlank()) {
            this.sfxPath = Paths.get(campaignPath, "SFX");
            this.worldsPath = Paths.get(campaignPath, "Worlds"); // Initialize Worlds path

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
    
    // Helper to inject the campaign service reference if needed for state awareness
    public static void setCampaignService(DndCampaignService service) {
        campaignServiceRef = service;
    }

    // --- Music Control ---

    public void playAmbientMusic(String filename) {
        stopBattleMusic(); // Ensure battle music is off
        stopAmbientMusic(); // Stop old ambient

        try {
            // Updated to search world folders first
            File file = findFileInActiveWorldOrGlobal(filename, "ambient");
            if (file != null) {
                ambientClip = AudioSystem.getClip();
                ambientClip.open(AudioSystem.getAudioInputStream(file));
                ambientClip.loop(Clip.LOOP_CONTINUOUSLY);
                ambientClip.start();
                System.out.println("Ciel Debug (Music): Playing ambient track " + file.getName());
            } else {
                System.out.println("Ciel Warning (Music): Could not find ambient track: " + filename);
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (Music): Failed to play ambient " + filename);
            e.printStackTrace();
        }
    }

    public void startBattleMusic(String filename) {
        if (ambientClip != null && ambientClip.isRunning()) {
            ambientPausePosition = ambientClip.getMicrosecondPosition();
            ambientClip.stop();
            System.out.println("Ciel Debug (Music): Paused ambient for battle.");
        }

        stopBattleMusic(); // Clean up previous battle

        try {
            // Updated to search world folders first
            File file = findFileInActiveWorldOrGlobal(filename, "battle");
            if (file != null) {
                battleClip = AudioSystem.getClip();
                battleClip.open(AudioSystem.getAudioInputStream(file));
                battleClip.loop(Clip.LOOP_CONTINUOUSLY);
                battleClip.start();
                System.out.println("Ciel Debug (Music): Battle music started: " + file.getName());
            } else {
                 System.out.println("Ciel Warning (Music): Could not find battle track: " + filename);
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (Music): Failed to play battle music " + filename);
            e.printStackTrace();
        }
    }

    public void startBossMusic(String filename) {
         // Reuses battle logic but searches 'boss' folder first
         startBattleMusic(filename); 
    }

    public void stopBattleMusic() {
        if (battleClip != null) {
            if (battleClip.isRunning()) battleClip.stop();
            battleClip.close();
            battleClip = null;
        }
        
        // Resume ambient if it was paused
        if (ambientClip != null && !ambientClip.isRunning()) {
            ambientClip.setMicrosecondPosition(ambientPausePosition);
            ambientClip.start();
            ambientClip.loop(Clip.LOOP_CONTINUOUSLY);
            System.out.println("Ciel Debug (Music): Resumed ambient music.");
        }
    }

    public void stopAmbientMusic() {
        if (ambientClip != null) {
            if (ambientClip.isRunning()) ambientClip.stop();
            ambientClip.close();
            ambientClip = null;
        }
    }

    // --- SFX (One-shot) ---

    public void playSound(String soundName) {
        if (sfxPath == null || !Files.exists(sfxPath)) return;

        new Thread(() -> {
            try {
                Optional<Path> fileOpt = findSoundFile(soundName);
                if (fileOpt.isPresent()) {
                    playWavOneShot(fileOpt.get().toFile());
                } else {
                    System.out.println("Ciel Warning (Sound): No sound file found for keyword '" + soundName + "'");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Smart Finder: Looks in the Active World folder (if set) first, then falls back to global SFX.
     * @param filename The simple filename (e.g., "forest.wav")
     * @param subfolder The contextual subfolder (ambient/battle/boss)
     */
    private File findFileInActiveWorldOrGlobal(String filename, String subfolder) {
        // 1. Check Active World Folder (if known)
        // We'd ideally get the active world name from DndCampaignService
        // For now, we search recursively if we can't pinpoint it, or rely on explicit relative paths in config.
        
        // Strategy: If filename contains path separators, use it as is.
        if (filename.contains("/") || filename.contains("\\")) {
             Path direct = Paths.get(Settings.getDndCampaignPath(), filename);
             if (Files.exists(direct)) return direct.toFile();
        }

        // 2. Global Fallback (SFX folder)
        Path globalPath = sfxPath.resolve(filename);
        if (Files.exists(globalPath)) return globalPath.toFile();
        
        return null; 
    }

    private Optional<Path> findSoundFile(String keyword) {
        try (Stream<Path> files = Files.walk(sfxPath)) {
            return files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".wav"))
                .filter(p -> p.getFileName().toString().toLowerCase().contains(keyword.toLowerCase()))
                .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void playWavOneShot(File file) {
        try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(file)) {
            DataLine.Info info = new DataLine.Info(Clip.class, audioStream.getFormat());
            Clip clip = (Clip) AudioSystem.getLine(info);
            clip.open(audioStream);
            clip.start();
            while (!clip.isRunning()) Thread.sleep(10);
            while (clip.isRunning()) Thread.sleep(10);
            clip.close();
        } catch (Exception e) {
            System.err.println("Ciel Error (Sound): Failed to play one-shot " + file.getName());
        }
    }
}