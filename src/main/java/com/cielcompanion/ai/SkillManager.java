package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.SpeechService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles the assimilation, tracking, and native execution of Skills.
 * Uses JSON metadata sidecars and direct file execution to bypass AV interference.
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
    
    public static void loadSkills() {
        System.out.println("Ciel Debug: Reloading local skills library.");
    }

    // Used primarily by the Skill Evolution Engine for merges
    public static void saveSkill(String skillName, String scriptContent) {
        try {
            String safeName = skillName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            String fileName = safeName + ".ps1"; // Defaulting to PS1 for evolved merges
            Path skillPath = SKILLS_DIR.resolve(fileName);
            
            Files.writeString(skillPath, scriptContent);

            // Generate basic metadata for the evolved skill
            JsonObject meta = new JsonObject();
            meta.addProperty("skill_name", safeName);
            meta.addProperty("file_name", fileName);
            meta.addProperty("description", "Autonomously merged/evolved skill: " + safeName);
            meta.addProperty("language", "powershell");
            meta.addProperty("created_at", System.currentTimeMillis());
            
            Files.writeString(SKILLS_DIR.resolve(safeName + ".json"), meta.toString());

            System.out.println("Ciel Debug: New Skill Assimilated -> " + safeName);
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to save assimilated skill.");
        }
    }

    public static void deleteSkill(String exactSkillName) {
        try {
            // Read metadata to find the exact script file to delete
            Path metaPath = SKILLS_DIR.resolve(exactSkillName + ".json");
            if (Files.exists(metaPath)) {
                JsonObject meta = JsonParser.parseString(Files.readString(metaPath)).getAsJsonObject();
                if (meta.has("file_name")) {
                    Files.deleteIfExists(SKILLS_DIR.resolve(meta.get("file_name").getAsString()));
                }
                Files.deleteIfExists(metaPath);
            } else {
                // Fallback aggressive cleanup if metadata is missing
                Files.deleteIfExists(SKILLS_DIR.resolve(exactSkillName + ".ps1"));
                Files.deleteIfExists(SKILLS_DIR.resolve(exactSkillName + ".py"));
                Files.deleteIfExists(SKILLS_DIR.resolve(exactSkillName + ".bat"));
            }
            System.out.println("Ciel Debug: Pruned obsolete skill -> " + exactSkillName);
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to prune skill.");
        }
    }

    public static String getAvailableSkillsString() {
        File folder = SKILLS_DIR.toFile();
        File[] metaFiles = folder.listFiles((dir, name) -> name.endsWith(".json"));
        
        if (metaFiles != null && metaFiles.length > 0) {
            List<String> skills = new ArrayList<>();
            for (File file : metaFiles) {
                try {
                    JsonObject meta = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
                    String name = meta.has("skill_name") ? meta.get("skill_name").getAsString() : file.getName().replace(".json", "");
                    String desc = meta.has("description") ? meta.get("description").getAsString() : "No description";
                    skills.add(name + " (" + desc + ")");
                } catch (Exception ignored) {}
            }
            if (!skills.isEmpty()) return String.join(", ", skills);
        }
        
        return "None";
    }

    public static Map<String, String> getAllSkillsDecrypted() {
        Map<String, String> skills = new HashMap<>();
        File folder = SKILLS_DIR.toFile();
        File[] metaFiles = folder.listFiles((dir, name) -> name.endsWith(".json"));
        
        if (metaFiles == null) return skills;

        for (File metaFile : metaFiles) {
            try {
                JsonObject meta = JsonParser.parseString(Files.readString(metaFile.toPath())).getAsJsonObject();
                if (!meta.has("file_name") || !meta.has("skill_name")) continue;

                String skillName = meta.get("skill_name").getAsString();
                String fileName = meta.get("file_name").getAsString();
                Path scriptPath = SKILLS_DIR.resolve(fileName);

                if (Files.exists(scriptPath)) {
                    String plainText = Files.readString(scriptPath);
                    String desc = meta.has("description") ? meta.get("description").getAsString() : "";
                    String lang = meta.has("language") ? meta.get("language").getAsString() : "";
                    
                    // Inject metadata context at the top of the code for the AI
                    String contextText = "/* Language: " + lang + " | Description: " + desc + " */\n" + plainText;
                    skills.put(skillName, contextText);
                }
            } catch (Exception ignored) {}
        }
        return skills;
    }

    public static String matchSkill(String input) {
        File folder = SKILLS_DIR.toFile();
        String lowerInput = input.toLowerCase().replace("the ", "").trim(); 
        
        File[] metaFiles = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (metaFiles != null) {
            for (File file : metaFiles) {
                try {
                    JsonObject meta = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
                    if (!meta.has("skill_name")) continue;

                    String rawName = meta.get("skill_name").getAsString();
                    String spacedName = rawName.replace("_", " ");
                    
                    if (lowerInput.contains(spacedName) || lowerInput.contains(rawName)) {
                        return rawName; 
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public static void executeSkill(String exactSkillName, String arguments, Runnable onComplete) {
        Path metaPath = SKILLS_DIR.resolve(exactSkillName + ".json");

        if (!Files.exists(metaPath)) {
            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Annoyed", 0.8, "Skill Error"));
            SpeechService.speakPreformatted("スキル エラー。ザ リクエステッド スキル イズ ノット イン マイ データベース。"); 
            if (onComplete != null) onComplete.run();
            return;
        }

        CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Focused", 0.8, "Executing Skill"));
        SpeechService.speakPreformatted("エクセキューティング アシミレイテッド スキル。"); 
        
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(metaPath)).getAsJsonObject();
            String fileName = meta.get("file_name").getAsString();
            String lang = meta.has("language") ? meta.get("language").getAsString().toLowerCase() : "powershell";
            Path scriptPath = SKILLS_DIR.resolve(fileName);

            if (!Files.exists(scriptPath)) {
                System.err.println("Ciel Error: Target script file missing -> " + fileName);
                return;
            }

            List<String> command = new ArrayList<>();

            // NATIVE FILE EXECUTION (Bypasses AMSI memory flagging)
            if (lang.equals("python") || lang.equals("py")) {
                command.add("python");
                command.add(scriptPath.toAbsolutePath().toString());
            } else if (lang.equals("bat") || lang.equals("cmd")) {
                command.add("cmd.exe");
                command.add("/c");
                command.add(scriptPath.toAbsolutePath().toString());
            } else {
                // Default to PowerShell execution from file
                command.add("powershell.exe");
                command.add("-NoProfile");
                command.add("-ExecutionPolicy");
                command.add("Bypass");
                command.add("-File");
                command.add(scriptPath.toAbsolutePath().toString());
            }

            // Append arguments safely
            if (arguments != null && !arguments.isBlank()) {
                command.addAll(Arrays.asList(arguments.split(" ")));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            
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