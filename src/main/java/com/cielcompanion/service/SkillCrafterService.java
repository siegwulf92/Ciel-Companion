package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.SkillManager;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;

public class SkillCrafterService {

    private static final String SKILLS_DIR = "C:\\Ciel Companion\\ciel\\skills";

    public static void initialize() {
        File dir = new File(SKILLS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        System.out.println("Ciel Debug: SkillCrafterService initialized at " + SKILLS_DIR);
    }

    public static void synthesizeNewSkill(String taskDescription) {
        synthesizeNewSkill(taskDescription, false);
    }

    public static void synthesizeNewSkill(String taskDescription, boolean isSilent) {
        System.out.println("Ciel Debug: Delegating task to Swarm Code Factory: " + taskDescription);
        
        if (!isSilent) {
            SpeechService.speakPreformatted("[Focused] Delegating logic synthesis to the Swarm. Please stand by.");
        }

        // Trigger the Python Swarm multi-agent loop
        String promptForPython = "[CREATE_SKILL] " + taskDescription;
        
        CompletableFuture.runAsync(() -> {
            String swarmResponse = AIEngine.generateDiaryEntrySync(promptForPython, "You are the Ciel Interface. Pass the Swarm's output to the user.");
            
            if (swarmResponse != null && swarmResponse.contains("```")) {
                compileAndSaveSkill(taskDescription, swarmResponse, isSilent);
            } else {
                if (!isSilent) {
                    SpeechService.speakPreformatted("[Concerned] The Swarm failed to verify a safe script for this task.");
                } else {
                    System.out.println("Ciel Debug: Silent swarm synthesis failed safety check. Aborting.");
                }
            }
        });
    }

    private static void compileAndSaveSkill(String originalTask, String swarmOutput, boolean isSilent) {
        try {
            Pattern pattern = Pattern.compile("```(bat|python|py|cmd|powershell|ps1)\\s*(.*?)\\s*```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(swarmOutput);
            
            if (matcher.find()) {
                String lang = matcher.group(1).toLowerCase();
                String code = matcher.group(2).trim();
                
                if (code.isBlank() || code.contains("Unsafe code detected")) {
                    if (!isSilent) SpeechService.speakPreformatted("The Swarm Reviewer rejected the code for safety reasons.");
                    return;
                }

                String extension = (lang.equals("python") || lang.equals("py")) ? ".py" : ".bat";
                if (lang.equals("powershell") || lang.equals("ps1")) extension = ".ps1"; // Support raw PS scripts from swarm
                
                String safeName = originalTask.replaceAll("(?i)^create a script to ", "")
                                              .replaceAll("[^a-zA-Z0-9]", "_")
                                              .toLowerCase();
                                              
                // Trim trailing underscores and limit length
                safeName = safeName.replaceAll("_+$", "");
                if (safeName.length() > 25) safeName = safeName.substring(0, 25).replaceAll("_+$", "");
                
                String fileName = safeName + extension;
                Path filePath = Paths.get(SKILLS_DIR, fileName);
                
                Files.writeString(filePath, code);
                
                SkillManager.loadSkills();
                
                System.out.println("Ciel Debug: Skill compiled successfully: " + fileName);
                
                if (!isSilent) {
                    SpeechService.speakPreformatted("[Happy] The new skill has been successfully compiled and assimilated. You may execute it at your discretion.");
                } else {
                    // Ambient Proactive Announcement
                    String friendlyName = safeName.replace("_", " ");
                    SpeechService.speakPreformatted("[Proud] I have autonomously developed and assimilated a new system skill to improve your workflow: " + friendlyName + ".");
                }
            } else {
                if (!isSilent) SpeechService.speakPreformatted("I could not parse the code block provided by the Swarm.");
            }
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to save synthesized skill.");
            e.printStackTrace();
            if (!isSilent) SpeechService.speakPreformatted("An I/O error occurred while saving the skill matrix.");
        }
    }
}