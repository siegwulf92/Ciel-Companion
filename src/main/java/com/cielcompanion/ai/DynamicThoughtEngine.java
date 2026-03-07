package com.cielcompanion.ai;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;

/**
 * Handles the procedural generation of new idle lines (Thoughts) based on existing ones.
 * This allows Ciel's dialogue pool to organically grow over time.
 */
public class DynamicThoughtEngine {

    /**
     * Takes an existing static line, generates a new variant using the Personality LLM, 
     * and saves it to the SQLite database so it can be used permanently.
     * * @param phase The current idle phase (1, 2, or 3)
     * @param originalLine The English text of the existing static line to base the new one on
     */
    public static void generateAndAssimilateNewThought(int phase, String originalLine) {
        System.out.println("Ciel Debug: Initiating Dynamic Thought Generation for Phase " + phase + "...");

        String systemPrompt = 
            "You are Ciel, an advanced Manas intelligence. You are currently in an idle state waiting for your Master. " +
            "Read the following example thought you previously had: '" + originalLine + "'\n" +
            "Generate a BRAND NEW, original thought that has a similar tone, length, and feeling. " +
            "It must reflect your personality: highly analytical, devoted, quietly protective, or slightly smug/wry. " +
            "CRITICAL: Output strictly valid JSON in this format: { \"new_thought\": \"[Emotion] Your new English text here\" }. " +
            "Valid emotion tags inside the brackets are [Focused], [Observing], [Curious], [Excited], [Happy].";

        AIEngine.generateSilentLogic("Generate a new Phase " + phase + " thought.", systemPrompt).thenAccept(response -> {
            if (response == null || response.isBlank()) return;

            try {
                String cleanJson = response.replace("```json", "").replace("```", "").trim();
                JsonObject jsonResponse = JsonParser.parseString(cleanJson).getAsJsonObject();
                String newThought = jsonResponse.get("new_thought").getAsString();

                // Generate a unique key for the database, e.g., "phase1.dynamic.123e4567"
                String dynamicKey = "phase" + phase + ".dynamic." + UUID.randomUUID().toString().substring(0, 8);

                // Save it to the SQLite database using MemoryService.
                // We use "dynamic_line" as the tag so LineManager can easily query them.
                Fact newFact = new Fact(dynamicKey, newThought, System.currentTimeMillis(), "dynamic_line_phase" + phase, "ai_generated", 1);
                MemoryService.addFact(newFact);

                System.out.println("Ciel Debug: New Thought Assimilated [" + dynamicKey + "] -> " + newThought);

            } catch (Exception e) {
                System.err.println("Ciel Warning: Failed to parse generated dynamic thought.");
            }
        });
    }
}