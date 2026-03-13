package com.cielcompanion.ai;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Autonomous background process that acts as Ciel's "Optimization/Evolution".
 * Scans the entire encrypted skill library, finds redundancies, merges them, and deletes old code.
 */
public class SkillEvolutionEngine {

    private static ScheduledExecutorService evolutionScheduler;

    public static void initialize() {
        evolutionScheduler = Executors.newSingleThreadScheduledExecutor();
        // Analyzes the library every 24 hours
        evolutionScheduler.scheduleWithFixedDelay(SkillEvolutionEngine::attemptEvolution, 2, 24, TimeUnit.HOURS);
        System.out.println("Ciel Debug: Skill Evolution Engine online. Autonomous merging active.");
    }

    private static void attemptEvolution() {
        Map<String, String> allSkills = SkillManager.getAllSkillsDecrypted();
        if (allSkills.size() < 2) return; // Needs at least 2 skills to find redundancies

        System.out.println("Ciel Debug: Initiating Global Skill Evolution Analysis...");

        StringBuilder payloadBuilder = new StringBuilder("Current Skill Library:\n");
        allSkills.forEach((name, code) -> {
            payloadBuilder.append("--- SKILL: ").append(name).append(" ---\n").append(code).append("\n\n");
        });

        String prompt = "You are Ciel, an advanced Manas. Your core directive is the absolute optimization of all systems. " +
            "Review the user's entire library of PowerShell scripts below. Look for redundant scripts (e.g., separate scripts for 'volume_up' and 'volume_down'). " +
            "If you find redundancies, write a single parameterized master script that merges their functionality. " +
            "If no merges are needed, set 'action' to 'keep'.\n\n" +
            "CRITICAL: Output STRICTLY valid JSON representing an array of actions. " +
            "Format: [ { \"action\": \"merge\", \"new_skill_name\": \"master_volume\", \"new_script\": \"code here\", \"delete_old_skills\": [\"volume_up\", \"volume_down\"], \"reason\": \"merged volume controls\" }, { \"action\": \"keep\" } ]\n\n" +
            payloadBuilder.toString();

        AIEngine.generateSilentLogic("Evolve and merge global skills", prompt).thenAccept(response -> {
            if (response == null || response.isBlank()) return;

            try {
                String cleanJson = response.replace("```json", "").replace("```", "").trim();
                JsonArray actions = JsonParser.parseString(cleanJson).getAsJsonArray();

                for (JsonElement element : actions) {
                    JsonObject actionObj = element.getAsJsonObject();
                    String action = actionObj.get("action").getAsString();

                    if ("merge".equals(action)) {
                        String newName = actionObj.get("new_skill_name").getAsString();
                        String newCode = actionObj.get("new_script").getAsString();
                        String reason = actionObj.get("reason").getAsString();

                        // 1. Save the new unified master skill securely
                        SkillManager.saveSkill(newName, newCode);

                        // 2. Delete the old redundant skills
                        JsonArray toDelete = actionObj.getAsJsonArray("delete_old_skills");
                        for (JsonElement delEl : toDelete) {
                            SkillManager.deleteSkill(delEl.getAsString());
                        }

                        // 3. Log the evolution in memory
                        String memoryText = "I autonomously merged redundant skills into '" + newName + "'. Reason: " + reason;
                        MemoryService.addFact(new Fact("evolution_merge_" + System.currentTimeMillis(), memoryText, System.currentTimeMillis(), "skill_evolution", "self", 1));

                        System.out.println("Ciel Debug: Skill Evolution Complete -> " + memoryText);
                    }
                }
            } catch (Exception e) {
                System.err.println("Ciel Warning: Failed to parse evolved global skill JSON.");
            }
        });
    }
}