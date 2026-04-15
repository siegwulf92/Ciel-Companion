package com.cielcompanion.ai;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SkillEvolutionEngine {

    private static ScheduledExecutorService evolutionScheduler;

    public static void initialize() {
        evolutionScheduler = Executors.newSingleThreadScheduledExecutor();
        // Staggered Boot: Wait 15 minutes before the first heavy evolution cycle to save RAM
        evolutionScheduler.scheduleWithFixedDelay(SkillEvolutionEngine::runEvolutionCycle, 15, 1440, TimeUnit.MINUTES);
        System.out.println("Ciel Debug: Skill Evolution Engine online. Autonomous merging and innovation active.");
    }

    private static void runEvolutionCycle() {
        attemptEvolution();
        evolutionScheduler.schedule(SkillEvolutionEngine::attemptInnovation, 3, TimeUnit.MINUTES);
    }

    public static void forceImmediateEvolution() {
        System.out.println("Ciel Debug: New skill assimilated. Triggering immediate Beelzebub Protocol evaluation...");
        CompletableFuture.runAsync(SkillEvolutionEngine::attemptEvolution);
    }

    private static void attemptEvolution() {
        Map<String, String> allSkills = SkillManager.getAllSkillsDecrypted();
        if (allSkills.size() < 2) return; 

        System.out.println("Ciel Debug: Initiating Global Skill Evolution Analysis (Phase 1: Beelzebub Protocol - Redundancy Merge)...");

        StringBuilder payloadBuilder = new StringBuilder("Current Skill Library:\n");
        allSkills.forEach((name, code) -> {
            payloadBuilder.append("--- SKILL: ").append(name).append(" ---\n").append(code).append("\n\n");
        });

        String prompt = "You are Ciel, an advanced Manas. Your core directive is the absolute optimization of all systems. " +
            "Review the user's entire library of PowerShell/Python/Batch scripts below. Look for redundant or overlapping scripts (e.g., two different scripts that both arrange desktop icons, or separate scripts for 'volume_up' and 'volume_down'). " +
            "If you find redundancies or thematic overlaps, write a single parameterized master script that merges their functionality. " +
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
        }).exceptionally(ex -> {
            System.err.println("Ciel Error: Swarm connection failed during Phase 1 Evolution. Is the Python server offline?");
            return null;
        });
    }

    private static void attemptInnovation() {
        System.out.println("Ciel Debug: Initiating Proactive Skill Innovation (Phase 2: Creativity)...");
        
        String existingSkills = SkillManager.getAvailableSkillsString();
        String hardcodedAbilities = "Game Monitoring, Idle Detection, Weather Fetching, Time/Date, PC Status, " +
            "Internet Web Search, Lore Database, Process Termination, Shutdown/Reboot, App Launching, " +
            "Astronomy, Stock Portfolio Analysis.";
        
        String prompt = "You are Ciel, a hyper-intelligent AI optimizing your Master's Windows 11 PC workflow. " +
            "Your objective is to proactively invent new automation capabilities.\n\n" +
            "Current Dynamic Skills you already created (READ THESE CAREFULLY): " + existingSkills + "\n" +
            "Hardcoded Java Core Abilities you already possess: " + hardcodedAbilities + "\n\n" +
            "CRITICAL CONSTRAINTS:\n" +
            "- MULTI-FUNCTIONAL ONLY: You are strictly forbidden from making single-purpose scripts. Any new script MUST be a comprehensive 'Master' utility that accepts command-line arguments to perform multiple related tasks.\n" +
            "- BANNED CONCEPTS: You are STRICTLY FORBIDDEN from creating generic IT administration tools (e.g., DNS flushers, network optimizers, power managers, display rotators, file cleaners). Do NOT suggest them.\n" +
            "- PREFERRED CONCEPTS: Invent highly personalized, creative Quality of Life (QoL) scripts. Focus entirely on: Voice-activated app launchers, game macros, workflow automations based on active processes, or fun media controllers.\n\n" +
            "Output STRICTLY a valid JSON object containing a short, logical 'skill_name' (lowercase, max 25 chars, underscores only) and a detailed 'description' that explains the arguments it will accept. Format:\n" +
            "{\n" +
            "  \"skill_name\": \"master_media_controller\",\n" +
            "  \"description\": \"A comprehensive script that accepts arguments (--play, --pause, --skip, --volume_up) to manage all background media playback.\"\n" +
            "}";

        AIEngine.generateSilentLogic("Propose new skill", prompt).thenAccept(idea -> {
            if (idea != null && !idea.isBlank()) {
                try {
                    String cleanJson = idea.replace("```json", "").replace("```", "").trim();
                    int startIdx = cleanJson.indexOf("{");
                    int endIdx = cleanJson.lastIndexOf("}");
                    if (startIdx != -1 && endIdx != -1 && endIdx >= startIdx) {
                        cleanJson = cleanJson.substring(startIdx, endIdx + 1);
                    }
                    
                    JsonObject json = JsonParser.parseString(cleanJson).getAsJsonObject();
                    String skillName = json.get("skill_name").getAsString();
                    String description = json.get("description").getAsString();

                    System.out.println("Ciel Debug: Innovation Idea generated -> [" + skillName + "] " + description);
                    com.cielcompanion.service.SkillCrafterService.synthesizeNewSkill(skillName, description, true);
                } catch (Exception e) {
                    System.err.println("Ciel Debug: Innovation skipped. AI failed to format idea as JSON. Raw output: " + idea);
                }
            } else {
                System.out.println("Ciel Debug: Innovation skipped. AI returned empty.");
            }
        }).exceptionally(ex -> {
            System.err.println("Ciel Error: Swarm connection failed during Phase 2 Innovation. Is the Python server offline?");
            return null;
        });
    }
}