package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.util.ProjectPaths;
import com.cielcompanion.ai.SkillManager;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SkillCrafterService {

    private static final String SKILLS_DIR = ProjectPaths.ciel().resolve("skills").toString();
    
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

        String normalizedTask = taskDescription.toLowerCase(java.util.Locale.ROOT);
        if (!isSilent && normalizedTask.matches("(?s).*(repair|fix|heal).*" )
                && normalizedTask.matches("(?s).*(stremio|media tracking|media commentary|media contract).*")) {
            if (!ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
                SpeechService.speakPreformatted("[Concerned] Media core repair requires your existing privileged mode authorization.");
                return;
            }
            if (!activelySynthesizing.compareAndSet(false, true)) return;
            System.out.println("[Media Repair] Authenticated request routed to staged core repair; dynamic skill writer bypassed.");
            CompletableFuture.supplyAsync(() -> requestMediaCoreRepair(taskDescription))
                    .orTimeout(15, TimeUnit.MINUTES).whenComplete((result, error) -> {
                        activelySynthesizing.set(false);
                        if (error != null) {
                            System.err.println("[Media Repair] Request incomplete: " + error.getMessage());
                            SpeechService.speakPreformatted("[Concerned] The media repair request did not finish. I have not confirmed any installed repair.");
                            return;
                        }
                        System.out.println("[Media Repair] " + result);
                        if (result != null && result.startsWith("MEDIA_REPAIR_APPLIED_RESTART_REQUIRED:")) {
                            SpeechService.speakPreformatted("[Proud] The media repair passed its regression checks and Java build. Restart me to activate it.");
                        } else {
                            SpeechService.speakPreformatted("[Observing] The media repair did not install a change. I retained the diagnostic details in my logs.");
                        }
                    });
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

                    promptForPython += "\n\n" + DeviceProfileService.context()
                        + "\nDevice-aware skills must read from ciel_tools import get_device_profile at execution time, validate status=available, and check local prerequisites. Never hardcode laptop/desktop hardware from shared memories.";
                    String swarmResponse = null;
                    try {
                        swarmResponse = AIEngine.generateSilentLogic(promptForPython, "You are the Ciel Interface. Pass the Swarm's output to the user.", "Requested Skill Code Generation").get(15, TimeUnit.MINUTES);
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

    private static String requestMediaCoreRepair(String taskDescription) {
        java.net.HttpURLConnection connection = null;
        try {
            Path keyFile = ProjectPaths.ciel().resolve("runtime").resolve("media_repair.key");
            Files.createDirectories(keyFile.getParent());
            if (!Files.exists(keyFile)) {
                String key = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
                Files.writeString(keyFile, key, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            String key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
            connection = (java.net.HttpURLConnection) new java.net.URL("http://localhost:8000/repair_media_contract").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(14 * 60 * 1000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Ciel-Media-Repair-Key", key);
            JsonObject payload = new JsonObject();
            payload.addProperty("request", taskDescription);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200) return "MEDIA_REPAIR_NOT_APPLIED: Repair service returned HTTP " + connection.getResponseCode();
            try (java.io.Reader input = new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                return com.google.gson.JsonParser.parseReader(input).getAsJsonObject().get("result").getAsString();
            }
        } catch (Exception failure) {
            return "MEDIA_REPAIR_NOT_APPLIED: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
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
                
                Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
                Files.writeString(tempPath, code, StandardCharsets.UTF_8);
                if (lang.equals("python")) {
                    boolean hasEntryPoint = Pattern.compile(
                            "(?m)^\\s*def\\s+execute\\s*\\(\\s*\\*args(?:\\s*,[^)]*)?\\s*\\)\\s*:")
                            .matcher(code).find();
                    if (!hasEntryPoint || !validatePythonSyntax(tempPath)) {
                        Files.deleteIfExists(tempPath);
                        System.err.println("Ciel Error: Generated Python skill rejected before publication: "
                                + (!hasEntryPoint ? "missing execute(*args) entry point" : "syntax validation failed"));
                        return false;
                    }
                }
                try {
                    Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException moveFallback) {
                    Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                }

                // CRITICAL FIX: Verify gaming status AFTER the massive generation window
                boolean isGamingNow = ShortTermMemoryService.getMemory().isInGamingSession() || "Gaming".equalsIgnoreCase(HabitTrackerService.getCurrentCategory());

                if (isDraftRequest) {
                    System.out.println("Ciel Debug: Core System Upgrade Draft routed safely to quarantine folder: " + fileName);
                    if (!isSilent && !isGamingNow) {
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
                
                String friendlyName = safeName.replace("_", " ");
                if (!isSilent && !isGamingNow) {
                    SpeechService.speakPreformatted("[Happy] I have autonomously developed and assimilated a new system skill to improve your workflow: " + friendlyName + ".");
                } else {
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
    
    private static String purgeArgparse(String code) {
        String cleaned = code.replaceAll("(?m)^.*\\bargparse\\b.*\\n?", "");
        cleaned = cleaned.replaceAll("(?m)^.*\\bparse_args\\b.*\\n?", "");
        cleaned = cleaned.replaceAll("(?m)^.*\\bsys\\.argv\\b.*\\n?", "");
        return cleaned;
    }
    
    private static String purgeGlobalLoops(String code) {
        return code.replaceAll("(?m)^while\\s+True\\s*:", "# BLOCKED GLOBAL INFINITE LOOP: while True:");
    }

    private static boolean validatePythonSyntax(Path script) {
        Process process = null;
        try {
            String checker = "import ast,pathlib,sys; "
                    + "ast.parse(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8-sig'), filename=sys.argv[1])";
            process = new ProcessBuilder(ProjectPaths.pythonExecutable(), "-c", checker,
                    script.toAbsolutePath().toString()).redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("Ciel Error: Generated Python syntax validation timed out.");
                return false;
            }
            if (process.exitValue() != 0) {
                String detail = new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8)
                        .replaceAll("[\\r\\n]+", " ").trim();
                System.err.println("Ciel Error: Generated Python syntax validation failed: " + detail);
                return false;
            }
            return true;
        } catch (IOException error) {
            System.err.println("Ciel Error: Generated Python syntax validator unavailable: "
                    + error.getClass().getSimpleName());
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
