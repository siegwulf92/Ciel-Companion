package com.cielcompanion.dnd;

import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the logic for updating mastery.md files across a flexible folder structure.
 * Designed to handle lowercase naming, spaces, and the specific multiversal mastery template.
 */
public class MasteryService {

    private final Path campaignRoot;

    public MasteryService() {
        String pathStr = Settings.getDndCampaignPath();
        this.campaignRoot = (pathStr != null && !pathStr.isBlank()) ? Paths.get(pathStr) : null;
    }

    /**
     * Finds the relevant mastery file and updates MP and logs.
     * @param playerInput The player or character name from voice input.
     * @param skillInput The skill name from voice input.
     */
    public void recordMeaningfulUse(String playerInput, String skillInput) {
        if (campaignRoot == null) {
            SpeechService.speak("Master, the campaign path is not set in settings.");
            return;
        }

        try {
            Path masteryFile = findMasteryFileFuzzy(playerInput, skillInput);
            
            if (masteryFile == null) {
                SpeechService.speakPreformatted("I'm sorry master, but I couldn't find a mastery record for " + skillInput + " belonging to " + playerInput + ".");
                return;
            }

            List<String> lines = Files.readAllLines(masteryFile, StandardCharsets.UTF_8);
            boolean updated = false;
            int mp = 0;
            int bonus = 0;
            int threshold = 10;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                
                // Update MP
                if (line.contains("Mastery Points (MP):")) {
                    mp = extractInteger(line) + 1;
                    lines.set(i, "Mastery Points (MP): " + mp);
                    updated = true;
                }
                
                // Track Current Bonus for level up checks
                if (line.contains("Current Mastery Bonus:")) {
                    bonus = extractInteger(line);
                }
                
                // Track current Threshold
                if (line.contains("Next Threshold:")) {
                    threshold = extractInteger(line);
                }
            }

            if (updated) {
                // Check for Level Up
                if (mp >= threshold) {
                    processLevelUp(lines, bonus, threshold, skillInput);
                }

                // Log the use
                appendUseLog(lines);

                // Write back
                Files.write(masteryFile, lines, StandardCharsets.UTF_8);
                SpeechService.speakPreformatted("Acknowledged. " + playerInput + "'s " + skillInput + " recorded. Mastery is now at " + mp + " points.");
            }

        } catch (IOException e) {
            SpeechService.speak("Master, I failed to access the file system for that mastery update.");
            e.printStackTrace();
        }
    }

    private Path findMasteryFileFuzzy(String player, String skill) throws IOException {
        Path heroesDir = campaignRoot.resolve("Heroes");
        if (!Files.exists(heroesDir)) return null;

        String targetPlayer = player.toLowerCase().replace(" ", "");
        String targetSkill = skill.toLowerCase().replace(" ", "");

        // Step 1: Find Player/Character Folder
        try (DirectoryStream<Path> playerStream = Files.newDirectoryStream(heroesDir)) {
            for (Path pFolder : playerStream) {
                String pName = pFolder.getFileName().toString().toLowerCase().replace(" ", "");
                if (pName.contains(targetPlayer)) {
                    // Step 2: Traverse into Character Subfolders (if they exist) or Skill folders
                    return findSkillFolderInTree(pFolder, targetSkill);
                }
            }
        }
        return null;
    }

    private Path findSkillFolderInTree(Path root, String targetSkill) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    String name = path.getFileName().toString().toLowerCase().replace(" ", "");
                    if (name.contains(targetSkill)) {
                        Path mastery = path.resolve("mastery.md");
                        if (Files.exists(mastery)) return mastery;
                    }
                    // Recursive check one level deeper for Character -> Skill structure
                    Path deepCheck = findSkillFolderInTree(path, targetSkill);
                    if (deepCheck != null) return deepCheck;
                }
            }
        }
        return null;
    }

    private int extractInteger(String line) {
        Matcher m = Pattern.compile("-?\\d+").matcher(line);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    private void processLevelUp(List<String> lines, int currentBonus, int oldThreshold, String skill) {
        int nextBonus = currentBonus + 1;
        
        // Handle Caps
        if (skill.toLowerCase().contains("perception") && nextBonus > 2) nextBonus = 2;
        
        int nextThreshold = switch (oldThreshold) {
            case 10 -> 20;
            case 20 -> 40;
            case 40 -> 80;
            default -> oldThreshold * 2;
        };

        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Current Mastery Bonus:")) {
                lines.set(i, "Current Mastery Bonus: +" + nextBonus);
            }
            if (lines.get(i).contains("Next Threshold:")) {
                lines.set(i, "Next Threshold: " + nextThreshold + " MP");
            }
        }
        SpeechService.speakPreformatted("System Alert: " + skill + " has reached a new threshold. Bonus is now +" + nextBonus + ".");
    }

    private void appendUseLog(List<String> lines) {
        String entry = "- " + LocalDate.now() + ": Meaningful success recorded by Ciel.";
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Meaningful Use Log:")) {
                lines.add(i + 2, entry);
                break;
            }
        }
    }
}