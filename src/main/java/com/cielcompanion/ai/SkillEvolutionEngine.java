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
        // Initial delay is 5 minutes, then it repeats every 24 hours (1440 minutes).
        evolutionScheduler.scheduleWithFixedDelay(SkillEvolutionEngine::runEvolutionCycle, 5, 1440, TimeUnit.MINUTES);
        System.out.println("Ciel Debug: Skill Evolution Engine online. Autonomous merging and innovation active (5m delay).");
    }

    private static void runEvolutionCycle() {
        attemptEvolution();
        
        // Pause briefly before running Phase 2 to avoid overwhelming the Swarm API
        evolutionScheduler.schedule(SkillEvolutionEngine::attemptInnovation, 3, TimeUnit.MINUTES);
    }

    // --- PHASE 1: MERGE REDUNDANT SKILLS (Beelzebub Protocol) ---
    private static void attemptEvolution() {
        Map<String, String> allSkills = SkillManager.getAllSkillsDecrypted();
        if (allSkills.size() < 2) return; 

        System.out.println("Ciel Debug: Initiating Global Skill Evolution Analysis (Phase 1: Beelzebub Protocol - Redundancy Merge)...");

        StringBuilder payloadBuilder = new StringBuilder("Current Skill Library:\n");
        allSkills.forEach((name, code) -> {
            payloadBuilder.append("--- SKILL: ").append(name).append(" ---\n").append(code).append("\n\n");
        });

        String prompt = "You are Ciel, an advanced Manas. Your core directive is the absolute optimization of all systems. " +
            "Review the user's entire library of PowerShell/Python scripts below. Look for redundant scripts (e.g., separate scripts for 'volume_up' and 'volume_down'). " +
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

                        String memoryText = "I autonomously utilized the Beelzebub Protocol to merge redundant skills into '" + newName + "'. Reason: " + reason;
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
        String hardcodedAbilities = "Game Monitoring/Tracking, Idle Detection, Weather Fetching, Time/Date, PC CPU/RAM Status, " +
            "Internet Web Search, D&D Lore Database, Process Termination, PC Shutdown/Reboot, App Launching, " +
            "Astronomy/Planet Fetching, Stock Portfolio Analysis, Memory Digestion.";
        
        String prompt = "You are Ciel, a hyper-intelligent AI optimizing your Master's Windows 11 PC workflow. " +
            "Your objective is to proactively invent new automation capabilities.\n\n" +
            "Current Dynamic Skills you created: " + existingSkills + "\n" +
            "Hardcoded Java Core Abilities you already possess (DO NOT REPLICATE THESE): " + hardcodedAbilities + "\n\n" +
            "CRITICAL CONSTRAINTS:\n" +
            "- DO NOT build game mode togglers, process monitors, memory managers, or application trackers. Your core Java engine already handles these perfectly.\n" +
            "- DO NOT duplicate existing skills.\n" +
            "- DO NOT suggest anything destructive, annoying, or that requires continuous background polling.\n\n" +
            "INSTRUCTION: Invent exactly ONE highly creative, genuinely useful new PowerShell/Python/Batch script automation that you DO NOT have yet, suited for a gamer, developer, or power-user. " +
            "Examples of valid ideas: 'Empty the recycle bin', 'Clear Windows temp files', 'Toggle hidden files visibility', 'Restart the audio service', 'Flush DNS cache', 'Organize the Downloads folder by file type'.\n\n" +
            "Output STRICTLY a valid JSON object containing a short, logical 'skill_name' (lowercase, max 25 chars, underscores only) and a detailed 'description'. Format:\n" +
            "{\n" +
            "  \"skill_name\": \"archive_screenshots\",\n" +
            "  \"description\": \"Create a script to automatically compress and archive screenshots older than 30 days from the Pictures folder into a dated ZIP file.\"\n" +
            "}";

        AIEngine.generateSilentLogic("Propose new skill", prompt).thenAccept(idea -> {
            if (idea != null && !idea.isBlank()) {
                try {
                    // Clean up markdown block formatting if the LLM adds it
                    String cleanJson = idea.replace("```json", "").replace("```", "").trim();
                    
                    // Isolate just the JSON block in case there is conversational text
                    int startIdx = cleanJson.indexOf("{");
                    int endIdx = cleanJson.lastIndexOf("}");
                    if (startIdx != -1 && endIdx != -1 && endIdx >= startIdx) {
                        cleanJson = cleanJson.substring(startIdx, endIdx + 1);
                    }
                    
                    JsonObject json = JsonParser.parseString(cleanJson).getAsJsonObject();
                    String skillName = json.get("skill_name").getAsString();
                    String description = json.get("description").getAsString();

                    System.out.println("Ciel Debug: Innovation Idea generated -> [" + skillName + "] " + description);
                    
                    // Pass the newly structured name and idea to the Coder Swarm silently
                    com.cielcompanion.service.SkillCrafterService.synthesizeNewSkill(skillName, description, true);
                } catch (Exception e) {
                    System.err.println("Ciel Debug: Innovation skipped. AI failed to format idea as JSON. Raw output: " + idea);
                }
            } else {
                System.out.println("Ciel Debug: Innovation skipped. AI returned empty.");
            }
        });
    }
}