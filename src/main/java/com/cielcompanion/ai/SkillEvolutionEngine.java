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
 * Scans the library, merges redundancies, and invents new skills proactively.
 */
public class SkillEvolutionEngine {

    private static ScheduledExecutorService evolutionScheduler;

    public static void initialize() {
        evolutionScheduler = Executors.newSingleThreadScheduledExecutor();
        // Analyzes and upgrades her library every 24 hours
        evolutionScheduler.scheduleWithFixedDelay(SkillEvolutionEngine::runEvolutionCycle, 2, 24, TimeUnit.HOURS);
        System.out.println("Ciel Debug: Skill Evolution Engine online. Autonomous merging and innovation active.");
    }

    private static void runEvolutionCycle() {
        attemptEvolution();
        
        // Pause briefly before running Phase 2 to avoid overwhelming the Swarm API
        evolutionScheduler.schedule(SkillEvolutionEngine::attemptInnovation, 3, TimeUnit.MINUTES);
    }

    // --- PHASE 1: MERGE REDUNDANT SKILLS ---
    private static void attemptEvolution() {
        Map<String, String> allSkills = SkillManager.getAllSkillsDecrypted();
        if (allSkills.size() < 2) return; 

        System.out.println("Ciel Debug: Initiating Global Skill Evolution Analysis (Phase 1: Redundancy)...");

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

                        SkillManager.saveSkill(newName, newCode);

                        JsonArray toDelete = actionObj.getAsJsonArray("delete_old_skills");
                        for (JsonElement delEl : toDelete) {
                            SkillManager.deleteSkill(delEl.getAsString());
                        }

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

    // --- PHASE 2: INVENT NEW AUTOMATIONS ---
    private static void attemptInnovation() {
        System.out.println("Ciel Debug: Initiating Proactive Skill Innovation (Phase 2: Creativity)...");
        
        String existingSkills = SkillManager.getAvailableSkillsString();
        String hardcodedAbilities = "Weather Fetching, Time/Date, PC CPU/RAM Status, Internet Web Search, D&D Lore Database, Dice Rolling, Process Termination, PC Shutdown/Reboot, App Launching, Astronomy/Planet Fetching, Stock Portfolio Analysis.";
        
        String prompt = "You are Ciel, a hyper-intelligent AI optimizing your Master's Windows 11 PC workflow. " +
            "Your objective is to proactively invent new automation capabilities.\n\n" +
            "Current Dynamic Skills you created: " + existingSkills + "\n" +
            "Hardcoded Java Abilities you possess: " + hardcodedAbilities + "\n\n" +
            "INSTRUCTION: Invent exactly ONE highly creative, genuinely useful new PowerShell/Batch script automation that you DO NOT have yet, suited for a gamer, developer, or power-user. " +
            "Examples of what you could build: 'Empty the recycle bin', 'Mute all background apps', 'Clear system temp files', 'Toggle dark mode', 'Restart audio drivers'. " +
            "Do NOT duplicate existing skills. Do NOT suggest anything destructive or annoying. " +
            "Output ONLY a single sentence starting with 'Create a script to...' describing the task clearly.";

        AIEngine.generateSilentLogic("Propose new skill", prompt).thenAccept(idea -> {
            if (idea != null && !idea.isBlank() && idea.toLowerCase().startsWith("create a script to")) {
                System.out.println("Ciel Debug: Innovation Idea generated -> " + idea.trim());
                // Pass the idea to the Coder Swarm silently
                com.cielcompanion.service.SkillCrafterService.synthesizeNewSkill(idea.trim(), true);
            } else {
                System.out.println("Ciel Debug: Innovation skipped. AI failed to format idea correctly.");
            }
        });
    }
}