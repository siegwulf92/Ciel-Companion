package com.cielcompanion.ai;

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

    private static final Path SKILLS_DIR = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "Skills");

    static {
        try {
            Files.createDirectories(SKILLS_DIR);
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to initialize Skills directory.");
        }
    }

    public static void saveSkill(String skillName, String scriptContent) {
        try {
            // Clean the skill name to be a safe file name
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

    public static void executeSkill(String skillName, Runnable onComplete) {
        String safeName = skillName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        Path scriptPath = SKILLS_DIR.resolve(safeName + ".ps1");

        if (!Files.exists(scriptPath)) {
            // Fallback: try replacing spaces with underscores if the router didn't
            safeName = skillName.toLowerCase().replace(" ", "_");
            scriptPath = SKILLS_DIR.resolve(safeName + ".ps1");
        }

        if (!Files.exists(scriptPath)) {
            SpeechService.speakPreformatted("[Annoyed] スキル エラー。ザ リクエステッド スキル イズ ノット イン マイ データベース。"); // Skill error. The requested skill is not in my database.
            if (onComplete != null) onComplete.run();
            return;
        }

        SpeechService.speakPreformatted("[Focused] エクセキューティング アシミレイテッド スキル。"); // Executing assimilated skill.
        
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toString());
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to execute skill " + safeName);
            e.printStackTrace();
        } finally {
            if (onComplete != null) onComplete.run();
        }
    }
}