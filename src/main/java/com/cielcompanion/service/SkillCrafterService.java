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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SkillCrafterService {

    private static final String SKILLS_DIR = "C:\\Ciel Companion\\ciel\\skills";
    
    private static final AtomicBoolean activelySynthesizing = new AtomicBoolean(false);

    public static void initialize() {
        File dir = new File(SKILLS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        System.out.println("Ciel Debug: SkillCrafterService initialized at " + SKILLS_DIR);
    }
    
    public static boolean isActivelySynthesizing() {
        return activelySynthesizing.get();
    }

    public static void synthesizeNewSkill(String taskDescription) {
        synthesizeNewSkill(taskDescription, false);
    }

    public static void synthesizeNewSkill(String taskDescription, boolean isSilent) {
        String safeName = taskDescription.replaceAll("(?i)^create a script to ", "")
                                         .replaceAll("[^a-zA-Z0-9]", "_")
                                         .toLowerCase();
        safeName = safeName.replaceAll("_+$", "");
        if (safeName.length() > 20) safeName = safeName.substring(0, 20).replaceAll("_+$", "");
        if (safeName.isBlank()) safeName = "dynamic_skill_" + System.currentTimeMillis();
        
        synthesizeNewSkill(safeName, taskDescription, isSilent);
    }

    public static void synthesizeNewSkill(String intendedName, String taskDescription, boolean isSilent) {
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

        activelySynthesizing.set(true);

        CompletableFuture.runAsync(() -> {
            try {
                int maxAttempts = 3;
                String previousFailures = "";
                boolean success = false;

                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    if (ShortTermMemoryService.getMemory().isInGamingSession()) {
                        System.out.println("Ciel Debug: Skill synthesis aborted mid-loop due to new gaming session.");
                        return;
                    }

                    System.out.println("Ciel Debug: Synthesis Attempt " + attempt + " of " + maxAttempts);

                    String promptForPython = "[CREATE_SKILL] Task: " + taskDescription + "\nIntended Script Name: " + intendedName +
                        "\n\nCRITICAL ARCHITECTURE RULES:\n" +
                        "1. The script MUST contain a function named 'execute(*args)'. This is the entry point Java will call.\n" +
                        "2. DO NOT USE ARGPARSE. DO NOT IMPORT ARGPARSE. DO NOT READ SYS.ARGV. Any attempt to use command-line arguments will instantly crash the server. You must extract all variables dynamically from the '*args' list passed to the execute function.\n" +
                        "3. NO GLOBAL LOOPS: Do not place 'while True' loops outside of functions. It will block the import process and hang the server.\n" +
                        "4. Output your code in a standard markdown block (e.g. ```python ... ```).";
                    
                    if (!previousFailures.isEmpty()) {
                        promptForPython += "\n\nWARNING: Previous attempts failed. Please read the failure log and adjust your approach. If you timed out, write a simpler/shorter script. If you failed safety, remove destructive commands. If you missed code blocks, ensure you use markdown fences.\nFailure Log:\n" + previousFailures;
                    }

                    // CRITICAL FIX: Use generateSilentLogic with a 15-minute timeout. Do NOT use generateDiaryEntrySync.
                    String swarmResponse = null;
                    try {
                        swarmResponse = AIEngine.generateSilentLogic(promptForPython, "You are the Ciel Interface. Pass the Swarm's output to the user.").get(15, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        System.err.println("Ciel Error: Skill synthesis future timed out or interrupted.");
                    }
                    
                    if (swarmResponse == null) {
                        previousFailures += "- Attempt " + attempt + " TIMED OUT (Exceeded 15 minutes). You took too long. Please write a simpler, more concise script and avoid over-engineering.\n";
                    } else if (swarmResponse.contains("```")) {
                        boolean compiled = compileAndSaveSkill(intendedName, taskDescription, swarmResponse, isSilent);
                        if (compiled) {
                            success = true;
                            break; 
                        } else {
                            previousFailures += "- Attempt " + attempt + " failed safety or compilation check.\n";
                        }
                    } else {
                        previousFailures += "- Attempt " + attempt + " failed to provide a valid code block. YOU MUST USE ``` TRIPLE BACKTICKS AROUND YOUR CODE.\n";
                    }
                }

                if (!success) {
                    if (!isSilent) {
                        SpeechService.speakPreformatted("[Concerned] The Swarm failed to verify a safe script for this task after " + maxAttempts + " attempts.");
                    } else {
                        System.out.println("Ciel Debug: Silent swarm synthesis failed safety checks after " + maxAttempts + " attempts. Aborting.");
                    }
                }
            } finally {
                activelySynthesizing.set(false);
            }
        });
    }

    private static boolean compileAndSaveSkill(String intendedName, String originalTask, String swarmOutput, boolean isSilent) {
        try {
            String finalName = intendedName;
            Pattern namePattern = Pattern.compile("FILE_NAME:\\s*(.+?)(?:\\n|\\r|$)", Pattern.CASE_INSENSITIVE);
            Matcher nameMatcher = namePattern.matcher(swarmOutput);
            if (nameMatcher.find()) {
                finalName = nameMatcher.group(1).trim();
            }

            Pattern pattern = Pattern.compile("```(?:bat|batch|python|py|cmd|powershell|ps1|markdown|md|java)?\\s*(.*?)\\s*```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(swarmOutput);
            
            if (matcher.find()) {
                String code = matcher.group(1).trim();
                
                String lang = "powershell";
                if (swarmOutput.toLowerCase().contains("```python") || swarmOutput.toLowerCase().contains("```py") || code.contains("def execute")) {
                    lang = "python";
                } else if (swarmOutput.toLowerCase().contains("```java") || swarmOutput.toLowerCase().contains("```markdown") || swarmOutput.toLowerCase().contains("```md")) {
                    lang = "markdown";
                } else if (swarmOutput.toLowerCase().contains("```bat") || swarmOutput.toLowerCase().contains("```cmd")) {
                    lang = "bat";
                }
                
                if (lang.equals("python")) {
                    code = purgeArgparse(code);
                    code = purgeGlobalLoops(code);
                }
                
                if (code.isBlank() || code.contains("Unsafe code detected")) {
                    System.out.println("Ciel Debug: Code rejected by Swarm Reviewer safety rules.");
                    return false; 
                }

                boolean isDraftRequest = lang.equals("markdown") || lang.equals("java") || originalTask.toLowerCase().contains("core system upgrade");

                String extension = ".ps1";
                if (isDraftRequest) extension = ".md";
                else if (lang.equals("python")) extension = ".py";
                else if (lang.equals("bat")) extension = ".bat";
                
                String safeName = finalName.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase().replaceAll("_+$", "");
                
                if (isDraftRequest && !safeName.startsWith("proposal_")) {
                    safeName = "proposal_" + safeName;
                }

                String fileName = safeName + extension;
                Path filePath;
                
                if (isDraftRequest) {
                    Path draftsDir = Paths.get(System.getProperty("user.dir"), "ciel", "requests", "drafts");
                    Files.createDirectories(draftsDir);
                    filePath = draftsDir.resolve(fileName);
                    
                    code = "# CORE UPGRADE PROPOSAL: " + finalName + "\n\n" +
                           "> **STATUS:** PENDING MASTER REVIEW\n" +
                           "> **INSTRUCTIONS:** Review the logic below. If approved, manually integrate the changes and recompile.\n\n" +
                           "```" + lang + "\n" + code + "\n```";
                } else {
                    filePath = Paths.get(SKILLS_DIR, fileName);
                }
                
                Files.writeString(filePath, code);

                if (isDraftRequest) {
                    System.out.println("Ciel Debug: Core System Upgrade Draft routed safely to quarantine folder: " + fileName);
                    if (!isSilent) {
                        SpeechService.speakPreformatted("[Proud] I have drafted a proposal for a core system upgrade. I have placed it in the requests/drafts folder for your review, Master.");
                    } else {
                        HabitTrackerService.queueNonCriticalAnnouncement("[Observing] I identified a potential architectural optimization. A draft proposal has been filed in your requests folder.", "Core Upgrade Proposal: " + safeName.replace("_", " "));
                    }
                    return true;
                }

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
                    String friendlyName = safeName.replace("_", " ");
                    HabitTrackerService.queueNonCriticalAnnouncement("[Proud] I have autonomously developed and assimilated a new system skill to improve your workflow: " + friendlyName + ".", "New Skill Synthesized: " + friendlyName);
                }

                com.cielcompanion.ai.SkillEvolutionEngine.forceImmediateEvolution();

                return true;
            } else {
                System.out.println("Ciel Debug: Could not parse code block from Swarm output:\n" + swarmOutput.substring(0, Math.min(200, swarmOutput.length())));
                return false;
            }
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to save synthesized skill or metadata.");
            e.printStackTrace();
            return false;
        }
    }
    
    // --- SANITIZATION METHODS ---
    
    private static String purgeArgparse(String code) {
        String cleaned = code.replaceAll("(?m)^.*\\bargparse\\b.*\\n?", "");
        cleaned = cleaned.replaceAll("(?m)^.*\\bparse_args\\b.*\\n?", "");
        cleaned = cleaned.replaceAll("(?m)^.*\\bsys\\.argv\\b.*\\n?", "");
        return cleaned;
    }
    
    private static String purgeGlobalLoops(String code) {
        return code.replaceAll("(?m)^while\\s+True\\s*:", "# BLOCKED GLOBAL INFINITE LOOP: while True:");
    }
}