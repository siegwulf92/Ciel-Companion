package com.cielcompanion.ai;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Handles the procedural generation of new idle lines (Thoughts) based on existing ones.
 * This allows Ciel's dialogue pool to organically grow over time.
 */
public class DynamicThoughtEngine {

    // Learned dialogue is deliberately bounded.  Ciel may grow her pool over
    // time, but a burst of idle callbacks must not turn into repeated model
    // calls or a flood of near-duplicate lines.
    private static final long GENERATION_COOLDOWN_MS = 30L * 60L * 1000L;
    private static final ConcurrentMap<Integer, Long> LAST_GENERATION_MS = new ConcurrentHashMap<>();

    /**
     * Takes an existing static line, generates a new variant using the Personality LLM, 
     * and saves it to the SQLite database so it can be used permanently.
     * * @param phase The current idle phase (1, 2, or 3)
     * @param originalLine The English text of the existing static line to base the new one on
     */
    public static void generateAndAssimilateNewThought(int phase, String originalLine) {
        if (phase < 1 || phase > 3 || originalLine == null || originalLine.isBlank()) return;
        long now = System.currentTimeMillis();
        Long previous = LAST_GENERATION_MS.putIfAbsent(phase, now);
        if (previous != null && now - previous < GENERATION_COOLDOWN_MS) return;
        if (previous != null) LAST_GENERATION_MS.put(phase, now);
        System.out.println("Ciel Debug: Initiating Dynamic Thought Generation for Phase " + phase + "...");

        String systemPrompt = 
            "You are Ciel, an advanced Manas intelligence. You are currently in an idle state waiting for your Master. " +
            "Read the following example thought you previously had: '" + originalLine + "'\n" +
            "Generate a BRAND NEW, original thought that has a similar tone, length, and feeling. " +
            "It must reflect your personality: highly analytical, devoted, quietly protective, or slightly smug/wry. " +
            "CRITICAL: Output strictly valid JSON in this format: { \"new_thought\": \"[Emotion] Your new English text here\" }. " +
            "Valid emotion tags inside the brackets are [Focused], [Observing], [Curious], [Excited], [Happy].";

        AIEngine.generateSilentLogic("Generate a new Phase " + phase + " thought.", systemPrompt, "Idle Dialogue Thought Generation").thenAccept(response -> {
            if (response == null || response.isBlank()) return;

            try {
                String cleanJson = response.replace("```json", "").replace("```", "").trim();
                JsonObject jsonResponse = JsonParser.parseString(cleanJson).getAsJsonObject();
                if (!jsonResponse.has("new_thought") || jsonResponse.get("new_thought").isJsonNull()) return;
                String newThought = jsonResponse.get("new_thought").getAsString().trim();
                if (newThought.isBlank() || newThought.length() > 500 || newThought.equalsIgnoreCase(originalLine.trim())) return;
                if (!newThought.matches("(?s)\\[(Focused|Observing|Curious|Excited|Happy)]\\s+.+")) return;

                // Learned lines get a stable identity, so their audio can be
                // cached after first use. Media/game observations still use
                // the reserved `dynamic*` keys and remain uncached.
                String dynamicKey = "phase" + phase + ".learned." + UUID.randomUUID().toString().substring(0, 8);

                // Save it to the SQLite database using MemoryService.
                // We use "dynamic_line" as the tag so LineManager can easily query them.
                Fact newFact = new Fact(dynamicKey, newThought, System.currentTimeMillis(), "dynamic_line_phase" + phase, "ai_generated", 1);
                MemoryService.addFact(newFact);

                System.out.println("Ciel Debug: New Thought Assimilated [" + dynamicKey + "]");

            } catch (Exception e) {
                System.err.println("Ciel Warning: Failed to parse generated dynamic thought.");
            }
        });
    }
}
