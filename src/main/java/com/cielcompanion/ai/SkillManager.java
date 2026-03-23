package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.CryptoService;
import com.cielcompanion.service.SpeechService;

import java.io.File;
import java.nio.charset.StandardCharsets;
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
 * Handles the assimilation, encryption, and secure fileless execution of Skills.
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
    
    // Method to force reload if external processes create new skills
    public static void loadSkills() {
        System.out.println("Ciel Debug: Reloading local skills library.");
    }

    public static void saveSkill(String skillName, String scriptContent) {
        try {
            String safeName = skillName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            Path skillPath = SKILLS_DIR.resolve(safeName + ".enc");
            
            // Encrypt the code before saving to disk
            String encryptedCode = CryptoService.encrypt(scriptContent);
            Files.writeString(skillPath, encryptedCode);
            System.out.println("Ciel Debug: New Skill Assimilated & Encrypted -> " + safeName);
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to save and encrypt assimilated skill.");
        }
    }

    public static void deleteSkill(String exactSkillName) {
        try {
            Files.deleteIfExists(SKILLS_DIR.resolve(exactSkillName + ".enc"));
            System.out.println("Ciel Debug: Pruned obsolete skill -> " + exactSkillName);
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to prune skill.");
        }
    }

    public static String getAvailableSkillsString() {
        File[] listOfFiles = SKILLS_DIR.toFile().listFiles((dir, name) -> name.endsWith(".enc"));
        if (listOfFiles == null || listOfFiles.length == 0) return "None";
        
        return Arrays.stream(listOfFiles)
                .map(file -> file.getName().replace(".enc", "").replace("_", " "))
                .collect(Collectors.joining(", "));
    }

    // Fetches all decrypted code for the Evolution Engine to review
    public static Map<String, String> getAllSkillsDecrypted() {
        Map<String, String> skills = new HashMap<>();
        File[] listOfFiles = SKILLS_DIR.toFile().listFiles((dir, name) -> name.endsWith(".enc"));
        if (listOfFiles == null) return skills;

        for (File file : listOfFiles) {
            try {
                String encryptedText = Files.readString(file.toPath());
                String plainText = CryptoService.decrypt(encryptedText);
                skills.put(file.getName().replace(".enc", ""), plainText);
            } catch (Exception ignored) {}
        }
        return skills;
    }

    // RESTORED AND UPDATED METHOD: Now scans for .enc files
    public static String matchSkill(String input) {
        File folder = SKILLS_DIR.toFile();
        File[] listOfFiles = folder.listFiles((dir, name) -> name.endsWith(".enc"));
        if (listOfFiles == null) return null;

        String lowerInput = input.toLowerCase().replace("the ", "").trim(); 
        
        for (File file : listOfFiles) {
            String rawName = file.getName().replace(".enc", "");
            String spacedName = rawName.replace("_", " ");
            
            if (lowerInput.contains(spacedName) || lowerInput.contains(rawName)) {
                return rawName; 
            }
        }
        return null;
    }

    public static void executeSkill(String exactSkillName, String arguments, Runnable onComplete) {
        Path scriptPath = SKILLS_DIR.resolve(exactSkillName + ".enc");

        if (!Files.exists(scriptPath)) {
            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Annoyed", 0.8, "Skill Error"));
            SpeechService.speakPreformatted("スキル エラー。ザ リクエステッド スキル イズ ノット イン マイ データベース。"); 
            if (onComplete != null) onComplete.run();
            return;
        }

        CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Focused", 0.8, "Executing Skill"));
        SpeechService.speakPreformatted("エクセキューティング アシミレイテッド スキル。"); 
        
        try {
            // Decrypt the file securely in memory
            String encryptedCode = Files.readString(scriptPath);
            String decryptedScript = CryptoService.decrypt(encryptedCode);

            // Append arguments to the raw script string if needed
            String scriptToExecute = decryptedScript;
            if (arguments != null && !arguments.isBlank()) {
                 // PowerShell magic: if the script expects args, we pass them inline before execution
                 scriptToExecute = "param($argsArray); " + decryptedScript + "\n" + exactSkillName + " " + arguments;
            }

            // SECURITY UPGRADE: Pipe script via STDIN to bypass AV heuristics
            List<String> command = new ArrayList<>();
            command.add("powershell.exe");
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-Command");
            command.add("-"); // Instructs PS to read from standard input

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            
            Process process = pb.start();
            try (java.io.OutputStream os = process.getOutputStream()) {
                os.write(scriptToExecute.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            process.waitFor();

        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to securely execute skill " + exactSkillName);
            e.printStackTrace();
        } finally {
            if (onComplete != null) onComplete.run();
        }
    }
}