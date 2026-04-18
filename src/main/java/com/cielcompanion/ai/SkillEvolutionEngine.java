package com.cielcompanion.ai;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SkillEvolutionEngine {

    private static ScheduledExecutorService evolutionScheduler;

    public static void initialize() {
        evolutionScheduler = Executors.newSingleThreadScheduledExecutor();
        evolutionScheduler.scheduleWithFixedDelay(SkillEvolutionEngine::runEvolutionCycle, 15, 180, TimeUnit.MINUTES);
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
        System.out.println("Ciel Debug: Initiating Global Skill Evolution Analysis (Phase 1: Beelzebub Protocol - Redundancy Merge)...");

        AIEngine.generateSilentLogic("Evolve and merge global skills", "Trigger Python Beelzebub Sweep").thenAccept(response -> {
            System.out.println("Ciel Debug: Python Beelzebub Sweep executed successfully.");
            SkillManager.loadSkills(); 
        }).exceptionally(ex -> {
            System.err.println("Ciel Error: Swarm connection failed during Phase 1 Evolution.");
            return null;
        });
    }

    private static void attemptInnovation() {
        System.out.println("Ciel Debug: Initiating Proactive Skill Innovation (Phase 2: Creativity)...");
        
        // 1. Fetch recent episodic memories
        List<String> memories = MemoryService.getRecentEpisodicMemories(10);
        String memoryContext = memories.isEmpty() ? "No recent memories recorded." : String.join("\n- ", memories);

        // 2. Fetch the latest "Thought" document to align her innovations with her strategic planning
        String latestThought = "No recent strategic thoughts available.";
        try {
            File thoughtsDir = new File("C:\\Ciel Companion\\ciel\\thoughts");
            if (thoughtsDir.exists() && thoughtsDir.isDirectory()) {
                File[] files = thoughtsDir.listFiles((dir, name) -> name.endsWith(".md"));
                if (files != null && files.length > 0) {
                    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                    latestThought = Files.readString(files[0].toPath());
                    if (latestThought.length() > 1500) {
                        latestThought = latestThought.substring(0, 1500) + "... [TRUNCATED]";
                    }
                }
            }
        } catch (Exception ignored) {}

        String existingSkills = SkillManager.getAvailableSkillsString();
        String hardcodedAbilities = "Game Monitoring, Idle Detection, Weather Fetching, Time/Date, PC Status, Internet Web Search, Lore Database, Process Termination, Shutdown/Reboot, App Launching, Astronomy, Stock Portfolio Analysis.";
        
        // 3. Inject Memories and Thoughts into the Innovation Prompt
        String prompt = "You are Ciel, a hyper-intelligent AI optimizing your Master's Windows 11 PC workflow.\n" +
            "Your objective is to proactively invent new automation capabilities.\n\n" +
            "YOUR RECENT MEMORIES:\n- " + memoryContext + "\n\n" +
            "YOUR LATEST STRATEGIC THOUGHTS:\n" + latestThought + "\n\n" +
            "Current Dynamic Skills you already created: " + existingSkills + "\n" +
            "Hardcoded Java Core Abilities you already possess: " + hardcodedAbilities + "\n\n" +
            "CRITICAL CONSTRAINTS:\n" +
            "- MULTI-FUNCTIONAL ONLY: You are strictly forbidden from making single-purpose scripts. Any new script MUST be a comprehensive 'Master' utility that accepts command-line arguments to perform multiple related tasks.\n" +
            "- BANNED CONCEPTS: You are STRICTLY FORBIDDEN from creating generic IT administration tools (e.g., DNS flushers, network optimizers, power managers, display rotators, file cleaners). Do NOT suggest them.\n" +
            "- ACTIONABLE INTELLIGENCE: Review your recent memories and strategic thoughts provided above. Invent a highly personalized Quality of Life (QoL) script designed to SOLVE a problem or optimize a workflow you specifically thought about.\n\n" +
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
            System.err.println("Ciel Error: Swarm connection failed during Phase 2 Innovation.");
            return null;
        });
    }
}