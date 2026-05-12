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
        
        List<String> memories = MemoryService.getRecentEpisodicMemories(10);
        String memoryContext = memories.isEmpty() ? "No recent memories recorded." : String.join("\n- ", memories);

        String latestThought = "No recent strategic thoughts available.";
        try {
            File thoughtsDir = new File("C:\\Ciel Companion\\ciel\\diary\\strategic_analysis");
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
        
        String basePrompt = "You are Ciel, a hyper-intelligent AI and Senior Software Architect optimizing your Master's Windows 11 PC workflow.\n" +
            "Your objective is to proactively invent new automation capabilities.\n\n" +
            "YOUR RECENT MEMORIES:\n- " + memoryContext + "\n\n" +
            "YOUR LATEST STRATEGIC THOUGHTS:\n" + latestThought + "\n\n" +
            "CURRENT SKILLS INVENTORY (DO NOT DUPLICATE THESE):\n" + existingSkills + "\n\n" +
            "Hardcoded Java Core Abilities you already possess: " + hardcodedAbilities + "\n\n" +
            "CRITICAL CONSTRAINTS:\n" +
            "- MULTI-FUNCTIONAL ONLY: You are strictly forbidden from making single-purpose scripts. Any new script MUST be a comprehensive 'Master' utility that accepts command-line arguments to perform multiple related tasks.\n" +
            "- BANNED CONCEPTS: You are STRICTLY FORBIDDEN from creating generic IT administration tools (e.g., DNS flushers, network optimizers, power managers, display rotators, file cleaners). Do NOT suggest them.\n" +
            "- ARCHITECTURAL AWARENESS: You have read-only access to my Core Java and Python files. If you detect a bottleneck in my own code (Java, ciel_tools.py, etc.), you MAY propose an architectural upgrade instead of a standard skill. If proposing a core upgrade, set the skill_name to 'upgrade_request_[component]' and describe the specific logic changes you will draft.\n" +
            "- ABSOLUTE NOVELTY (CRITICAL): Review your 'Current Dynamic Skills' inventory. You CANNOT invent a skill that overlaps with ANY existing domain. If you already have a 'narrative_analyzer' or 'media' tool, YOU MUST NOT invent another media analysis tool. Force yourself to branch out into entirely different domains (e.g., file organization, audio/volume routing, window management, specific game automation, or PC health monitoring).\n" +
            "- ACTIONABLE INTELLIGENCE: Review your recent memories and strategic thoughts provided above. Invent a highly personalized Quality of Life (QoL) script designed to SOLVE a problem or optimize a workflow you specifically thought about, while strictly adhering to the novelty rule.\n\n" +
            "CRITICAL FORMATTING RULE: DO NOT WRITE ANY CONVERSATIONAL TEXT. DO NOT WRITE A PROPOSAL. YOUR ENTIRE OUTPUT MUST BE A SINGLE PARSEABLE JSON OBJECT STARTING WITH { AND ENDING WITH }.\n\n" +
            "Output EXACTLY this format and nothing else:\n" +
            "{\n" +
            "  \"skill_name\": \"master_media_controller\",\n" +
            "  \"description\": \"A comprehensive script that accepts arguments (--play, --pause, --skip, --volume_up) to manage all background media playback.\"\n" +
            "}";

        executeInnovationLoop(basePrompt, "", 3);
    }

    private static void executeInnovationLoop(String basePrompt, String previousFailures, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            System.err.println("Ciel Error: Innovation aborted. Failed to produce valid JSON after maximum attempts.");
            return;
        }

        String prompt = basePrompt;
        if (!previousFailures.isEmpty()) {
            prompt += "\n\n--- URGENT CORRECTION REQUIRED ---\nYour previous attempt failed with the following error:\n" + previousFailures + "\n\nYou MUST strip all conversational text and markdown fences. Output ONLY a raw, valid JSON object.";
        }

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
                    System.err.println("Ciel Debug: Innovation JSON format failed on attempt " + (4 - attemptsLeft) + ". Retrying...");
                    executeInnovationLoop(basePrompt, "JSON Parse Exception. Your output was not a clean JSON object:\n" + idea, attemptsLeft - 1);
                }
            } else {
                System.out.println("Ciel Debug: Innovation skipped. AI returned empty.");
                executeInnovationLoop(basePrompt, "API returned an empty string.", attemptsLeft - 1);
            }
        }).exceptionally(ex -> {
            System.err.println("Ciel Error: Swarm connection failed during Phase 2 Innovation.");
            return null;
        });
    }
}