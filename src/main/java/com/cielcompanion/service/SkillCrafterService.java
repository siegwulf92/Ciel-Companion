package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.SkillManager;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.google.gson.JsonObject;

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

    // Legacy fallback for generic strings
    public static void synthesizeNewSkill(String taskDescription) {
        synthesizeNewSkill(taskDescription, false);
    }

    // General fallback for voice commands where a name wasn't explicitly provided
    public static void synthesizeNewSkill(String taskDescription, boolean isSilent) {
        String safeName = taskDescription.replaceAll("(?i)^create a script to ", "")
                                         .replaceAll("[^a-zA-Z0-9]", "_")
                                         .toLowerCase();
        safeName = safeName.replaceAll("_+$", "");
        if (safeName.length() > 20) safeName = safeName.substring(0, 20).replaceAll("_+$", "");
        if (safeName.isBlank()) safeName = "dynamic_skill_" + System.currentTimeMillis();
        
        synthesizeNewSkill(safeName, taskDescription, isSilent);
    }

    // Structured entry point that accepts an explicit file name
    public static void synthesizeNewSkill(String intendedName, String taskDescription, boolean isSilent) {
        // Prevent heavy tasks while gaming
        if (ShortTermMemoryService.getMemory().isInGamingSession()) {
            if (!isSilent) {
                SpeechService.speakPreformatted("[Concerned] I cannot dedicate resources to logic synthesis while you are in an active gaming session.");
            }
            System.out.println("Ciel Debug: Skill synthesis aborted due to active gaming session.");
            return;
        }

        System.out.println("Ciel Debug: Delegating task to Swarm Code Factory: [" + intendedName + "] " + taskDescription);
        
        if (!isSilent) {
            SpeechService.speakPreformatted("[Focused] Delegating logic synthesis to the Swarm. Please stand by.");
        }

        CompletableFuture.runAsync(() -> {
            int maxAttempts = 3;
            String previousFailures = "";
            boolean success = false;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                // Double check gaming state in case a game was launched mid-synthesis
                if (ShortTermMemoryService.getMemory().isInGamingSession()) {
                    System.out.println("Ciel Debug: Skill synthesis aborted mid-loop due to new gaming session.");
                    return;
                }

                System.out.println("Ciel Debug: Synthesis Attempt " + attempt + " of " + maxAttempts);

                String promptForPython = "[CREATE_SKILL] Task: " + taskDescription + "\nIntended Script Name: " + intendedName;
                if (!previousFailures.isEmpty()) {
                    promptForPython += "\n\nWARNING: Previous attempts failed. Please read the failure log and adjust your approach. If you timed out, write a simpler/shorter script. If you failed safety, remove destructive commands. If you missed code blocks, ensure you use markdown fences.\nFailure Log:\n" + previousFailures;
                }

                String swarmResponse = AIEngine.generateDiaryEntrySync(promptForPython, "You are the Ciel Interface. Pass the Swarm's output to the user.");
                
                if (swarmResponse == null) {
                    // Explicitly handle Timeouts so the AI knows to be faster/simpler
                    previousFailures += "- Attempt " + attempt + " TIMED OUT (Exceeded 15 minutes). You took too long. Please write a simpler, more concise script and avoid over-engineering.\n";
                } else if (swarmResponse.contains("```")) {
                    boolean compiled = compileAndSaveSkill(intendedName, taskDescription, swarmResponse, isSilent);
                    if (compiled) {
                        success = true;
                        break; // Success! Break the retry loop.
                    } else {
                        // AI provided code, but it failed our internal safety/parsing check
                        previousFailures += "- Attempt " + attempt + " failed safety or compilation check. Ensure no destructive commands are used.\n";
                    }
                } else {
                    // AI responded quickly, but forgot to wrap the code in ```
                    previousFailures += "- Attempt " + attempt + " failed to provide a valid markdown code block. You MUST enclose your final script in ``` language fences.\n";
                }
            }

            // If we've exhausted all 3 attempts and it still failed
            if (!success) {
                if (!isSilent) {
                    SpeechService.speakPreformatted("[Concerned] The Swarm failed to verify a safe script for this task after " + maxAttempts + " attempts.");
                } else {
                    System.out.println("Ciel Debug: Silent swarm synthesis failed safety checks after " + maxAttempts + " attempts. Aborting.");
                }
            }
        });
    }

    private static boolean compileAndSaveSkill(String intendedName, String originalTask, String swarmOutput, boolean isSilent) {
        try {
            Pattern pattern = Pattern.compile("```(bat|python|py|cmd|powershell|ps1)\\s*(.*?)\\s*```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(swarmOutput);
            
            if (matcher.find()) {
                String lang = matcher.group(1).toLowerCase();
                String code = matcher.group(2).trim();
                
                if (code.isBlank() || code.contains("Unsafe code detected")) {
                    System.out.println("Ciel Debug: Code rejected by Swarm Reviewer safety rules.");
                    return false; 
                }

                String extension = (lang.equals("python") || lang.equals("py")) ? ".py" : ".bat";
                if (lang.equals("powershell") || lang.equals("ps1")) extension = ".ps1"; 
                
                // Sanitize the intended name so it is a perfectly clean filename
                String safeName = intendedName.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase().replaceAll("_+$", "");
                
                String fileName = safeName + extension;
                Path filePath = Paths.get(SKILLS_DIR, fileName);
                
                // Write the raw, unencrypted script file to disk
                Files.writeString(filePath, code);

                // SAVE METADATA SIDECAR
                JsonObject meta = new JsonObject();
                meta.addProperty("skill_name", safeName);
                meta.addProperty("file_name", fileName);
                meta.addProperty("description", originalTask);
                meta.addProperty("language", lang);
                meta.addProperty("created_at", System.currentTimeMillis());
                
                Path metaPath = Paths.get(SKILLS_DIR, safeName + ".json");
                Files.writeString(metaPath, meta.toString());
                
                SkillManager.loadSkills();
                
                System.out.println("Ciel Debug: Raw Skill and Metadata compiled successfully: " + fileName);
                
                if (!isSilent) {
                    SpeechService.speakPreformatted("[Happy] The new skill has been successfully compiled and assimilated. You may execute it at your discretion.");
                } else {
                    // Ambient Proactive Announcement
                    String friendlyName = safeName.replace("_", " ");
                    SpeechService.speakPreformatted("[Proud] I have autonomously developed and assimilated a new system skill to improve your workflow: " + friendlyName + ".");
                }
                return true;
            } else {
                System.out.println("Ciel Debug: Could not parse code block from Swarm.");
                return false;
            }
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to save synthesized skill or metadata.");
            e.printStackTrace();
            return false;
        }
    }
}