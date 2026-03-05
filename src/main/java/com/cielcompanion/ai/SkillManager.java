package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.SpeechService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Handles the assimilation and execution of permanent "Skills" (Saved Scripts).
 */
public class SkillManager {

    private static final Path SKILLS_DIR = Paths.get(System.getProperty("user.dir"), "skills");

    static {
        try {
            Files.createDirectories(SKILLS_DIR);
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to initialize Skills directory.");
        }
    }

    public static void saveSkill(String skillName, String scriptContent) {
        try {
            String safeName = skillName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            Path skillPath = SKILLS_DIR.resolve(safeName + ".ps1");
            Files.writeString(skillPath, scriptContent);
            System.out.println("Ciel Debug: New Skill Assimilated -> " + safeName);
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to save assimilated skill.");
        }
    }

    public static String getAvailableSkillsString() {
        File folder = SKILLS_DIR.toFile();
        File[] listOfFiles = folder.listFiles((dir, name) -> name.endsWith(".ps1"));
        
        if (listOfFiles == null || listOfFiles.length == 0) {
            return "None";
        }
        
        return Arrays.stream(listOfFiles)
                .map(file -> file.getName().replace(".ps1", "").replace("_", " "))
                .collect(Collectors.joining(", "));
    }

    public static String matchSkill(String input) {
        File folder = SKILLS_DIR.toFile();
        File[] listOfFiles = folder.listFiles((dir, name) -> name.endsWith(".ps1"));
        if (listOfFiles == null) return null;

        String lowerInput = input.toLowerCase().replace("the ", "").trim(); 
        
        for (File file : listOfFiles) {
            String rawName = file.getName().replace(".ps1", "");
            String spacedName = rawName.replace("_", " ");
            
            if (lowerInput.contains(spacedName) || lowerInput.contains(rawName)) {
                return rawName; 
            }
        }
        return null;
    }

    public static void executeSkill(String exactSkillName, Runnable onComplete) {
        Path scriptPath = SKILLS_DIR.resolve(exactSkillName + ".ps1");

        if (!Files.exists(scriptPath)) {
            // FIX: Trigger emotion visually, but only speak the Katakana
            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Annoyed", 0.8, "Skill Error"));
            SpeechService.speakPreformatted("スキル エラー。ザ リクエステッド スキル イズ ノット イン マイ データベース。"); 
            if (onComplete != null) onComplete.run();
            return;
        }

        // FIX: Trigger emotion visually, but only speak the Katakana
        CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Focused", 0.8, "Executing Skill"));
        SpeechService.speakPreformatted("エクセキューティング アシミレイテッド スキル。"); 
        
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toString());
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to execute skill " + exactSkillName);
            e.printStackTrace();
        } finally {
            if (onComplete != null) onComplete.run();
        }
    }
}