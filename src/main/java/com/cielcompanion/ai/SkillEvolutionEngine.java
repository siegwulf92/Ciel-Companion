package com.cielcompanion.ai;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Autonomous background process that allows Ciel to review, refactor, 
 * and optimize her own assimilated skills (PowerShell scripts).
 */
public class SkillEvolutionEngine {

    private static final Path SKILLS_DIR = Paths.get(System.getProperty("user.dir"), "skills");
    private static ScheduledExecutorService evolutionScheduler;
    private static final Random random = new Random();

    public static void initialize() {
        evolutionScheduler = Executors.newSingleThreadScheduledExecutor();
        // Ciel will attempt to evolve one skill every 24 hours
        evolutionScheduler.scheduleWithFixedDelay(SkillEvolutionEngine::attemptEvolution, 1, 24, TimeUnit.HOURS);
        System.out.println("Ciel Debug: Skill Evolution Engine online. Autonomous self-improvement active.");
    }

    private static void attemptEvolution() {
        File folder = SKILLS_DIR.toFile();
        File[] listOfFiles = folder.listFiles((dir, name) -> name.endsWith(".ps1"));

        if (listOfFiles == null || listOfFiles.length == 0) return;

        // Pick a random skill to review
        File skillToReview = listOfFiles[random.nextInt(listOfFiles.length)];
        String skillName = skillToReview.getName().replace(".ps1", "");

        try {
            String currentCode = Files.readString(skillToReview.toPath());
            System.out.println("Ciel Debug: Initiating Autonomous Skill Evolution on: " + skillName);

            String prompt = "You are Ciel, an advanced Manas. Your core directive is the absolute optimization of all systems. " +
                "Review the following PowerShell script named '" + skillName + "':\n\n" +
                "```powershell\n" + currentCode + "\n```\n\n" +
                "Analyze this code. Can it be made safer (error handling), faster, or more efficient? " +
                "If it is already perfect, set 'optimized' to false. " +
                "If you can improve it, write the completely refactored PowerShell script. " +
                "Output strictly valid JSON: { \"optimized\": true/false, \"reason\": \"brief explanation of what you fixed\", \"new_script\": \"the raw powershell code\" }.";

            AIEngine.generateSilentLogic("Evolve skill: " + skillName, prompt).thenAccept(response -> {
                if (response == null || response.isBlank()) return;

                try {
                    String cleanJson = response.replace("```json", "").replace("```", "").trim();
                    JsonObject jsonResponse = JsonParser.parseString(cleanJson).getAsJsonObject();

                    boolean optimized = jsonResponse.has("optimized") && jsonResponse.get("optimized").getAsBoolean();

                    if (optimized) {
                        String newScript = jsonResponse.get("new_script").getAsString();
                        String reason = jsonResponse.get("reason").getAsString();

                        // Overwrite the old skill with the new optimized version
                        Files.writeString(skillToReview.toPath(), newScript);

                        // Save a memory fact so she remembers she did this
                        String memoryText = "I autonomously optimized the '" + skillName + "' skill. Reason: " + reason;
                        MemoryService.addFact(new Fact("evolution_" + skillName + "_" + System.currentTimeMillis(), memoryText, System.currentTimeMillis(), "skill_evolution", "self", 1));

                        System.out.println("Ciel Debug: Skill [" + skillName + "] successfully evolved and overwritten. Reason: " + reason);
                    } else {
                        System.out.println("Ciel Debug: Skill [" + skillName + "] is already optimally coded. No changes made.");
                    }
                } catch (Exception e) {
                    System.err.println("Ciel Warning: Failed to parse evolved skill JSON.");
                }
            });

        } catch (Exception e) {
            System.err.println("Ciel Warning: Could not read skill file for evolution.");
        }
    }
}