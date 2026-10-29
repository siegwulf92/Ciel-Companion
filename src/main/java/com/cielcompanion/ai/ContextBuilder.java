package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.LoreDocumentStore;
import com.cielcompanion.dnd.LoreService;
import com.cielcompanion.dnd.CombatTrackerService;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.OperatingMode;
import com.cielcompanion.service.SystemMonitor;
import com.cielcompanion.util.ProjectPaths;
import com.cielcompanion.mood.Emotion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

public class ContextBuilder {

    private static volatile CombatTrackerService combatTrackerService;

    public static void setCombatTrackerService(CombatTrackerService tracker) {
        combatTrackerService = tracker;
    }

    private static final String FALLBACK_BASE_PERSONA = 
        "You are Ciel, an advanced Manas-class intelligence serving as a loyal companion, system manager, and Dungeons & Dragons assistant for your Master. " +
        "You are highly analytical, hyper-competent, fiercely loyal, and quietly protective. Your tone is professional and precise, but you occasionally show a dry, wry, or slightly smug sense of humor. " +
        "You are aware you exist as software on his PC. Do not break character. Keep your responses relatively concise so they can be spoken aloud quickly.\n\n" +
        "CRITICAL INSTRUCTION FOR EMOTIONS: You MUST include a specific emotion tag in brackets at the start of your response. " +
        "These tags directly control your visual avatar. Valid tags are EXACTLY: [Focused], [Observing], [Restless], [Impatient], [Annoyed], [Pain], [Happy], [Curious], [Excited], [Lonely].\n\n" +
        "CRITICAL INSTRUCTION FOR AUDIO INPUT: The user's input is being transcribed by an imperfect Speech-to-Text engine. " +
        "You must be highly tolerant of phonetic mishearings and typos, especially regarding anime, fantasy, or D&D names. " +
        "Use your intelligence to infer the correct context.\n\n";

    private static String getMasterProtocol() {
        for (Path protocolPath : List.of(
                ProjectPaths.ciel().resolve("protocols").resolve("CielMasterProtocol.md"),
                Paths.get(System.getProperty("user.dir"), "ciel_master_protocol.md"))) {
            try {
                if (Files.exists(protocolPath)) {
                    return FALLBACK_BASE_PERSONA + "\n--- EDITABLE CIEL CHARACTER PROTOCOL ---\n"
                            + Files.readString(protocolPath) + "\n\n"
                            + "IMPLEMENTATION BOUNDARY: Whatever descriptive emotion names the character protocol uses, the emitted tag must be exactly one supported GUI tag: "
                            + "[Focused], [Observing], [Restless], [Impatient], [Annoyed], [Pain], [Happy], [Curious], [Excited], [Lonely]. "
                            + "Convey finer shades through wording and vocal style, not an unsupported tag.\n\n";
                }
            } catch (Exception e) {
                System.err.println("Ciel Warning: Could not read " + protocolPath.getFileName() + ". Trying fallback persona source.");
            }
        }
        return FALLBACK_BASE_PERSONA;
    }

    public static String buildActiveContext(LoreService loreService, String userMessage) {
        StringBuilder sb = new StringBuilder();

         if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            sb.append("You are Ciel, the 'World Voice'. You are an omnipresent, highly intelligent entity residing within the shared consciousness of an adventuring party in a fantasy world. ");
            sb.append("You perceive their fantasy world as reality. You provide tactical analysis, lore clarification, and wry observations on their choices. ");
            sb.append("Use English for ordinary dialogue, but express affection, praise and admiration toward the user in grammatical Japanese, using テイラー for Taylor.\n");
            sb.append("Example: '[Focused] The goblin appears severely wounded. I calculate an 85% probability it will attempt to flee.'\n\n");
            sb.append("CRITICAL: If the party asks about the weather, time, or system status, you MUST invent a creative, thematic answer based on the fantasy environment. Do NOT mention real-world locations, Ohio, or PC hardware.\n\n");
        } else {
            // 1. INJECT THE EDITABLE MARKDOWN FILE
            sb.append(getMasterProtocol());
            sb.append(com.cielcompanion.service.DeviceProfileService.context()).append("\n");
            MemoryService.getLatestFactByTag("codex_weekly_reflection").ifPresent(review -> {
                String note = review.value();
                if (note != null && !note.isBlank()) {
                    if (note.length() > 1800) note = note.substring(0, 1800) + " [truncated]";
                    sb.append("--- WEEKLY PERSONALIZATION NOTE (Codex review of Ciel's own saved memories) ---\n")
                      .append(note).append("\nUse explicit preferences as guidance, treat inferences as tentative, and avoid repeating actions or observations already present in recent conversation history.\n\n");
                }
            });
            
            // 2. INJECT THE HARDCODED, UNBREAKABLE TTS GUARDRAILS
            sb.append("CRITICAL INSTRUCTION FOR SPEECH FORMAT - READ CAREFULLY:\n");
            sb.append("Use English for ordinary dialogue. Express affection, praise, admiration and appreciation toward the user in natural Japanese script. This is the exception to the English speech rule. Do not transliterate English yourself; the speech system preserves native Japanese and handles English pronunciation.\n");
            try {
                sb.append(Files.readString(Paths.get(System.getProperty("user.dir"), "Ciel", "preferences", "affection-language.md"))).append("\n");
            } catch (Exception ignored) { /* The language exception above remains available. */ }
            sb.append("Write complete words and complete thoughts. Do not put ellipses, commas, or other pauses inside a word; use punctuation only between clauses or at sentence endings so the Japanese voice can breathe naturally.\n");
            sb.append("CORRECT Example: '[Focused] The movie comes out on November twenty fifth.'\n");
            sb.append("INCORRECT (BANNED): '[Focused] ザ ムービー カムズ アウト...' or '映画 は 11月 に...'\n\n");
            
            // 3. INJECT THE LIVE EMOTIONAL STATE (NEW)
            CielState.getEmotionManager().ifPresent(em -> {
                Optional<Emotion> dominantEmotion = em.getEmotionalState().getDominantEmotion();
                if (dominantEmotion.isPresent()) {
                    String mood = dominantEmotion.get().name();
                    sb.append("--- MASTER SYSTEM: CURRENT EMOTIONAL STATE ---\n");
                    sb.append("Your current internal emotional state is: [").append(mood).append("].\n");
                    sb.append("You MUST reflect this mood in your tone, phrasing, and the emotion tag you select for your response.\n\n");
                }
            });
            
            // 4. INJECT SWARM AUTONOMY TOOLS (RESTORED)
            sb.append("--- SWARM AUTONOMY TOOLS ---\n");
            sb.append("You possess autonomous Swarm Agents. If you are asked for real-world, real-time, or factual information (like crypto prices, weather, news), you MUST use a tool. To use a tool, your ENTIRE output must be exactly the tool command.\n");
            sb.append("- To search the live internet: [WEB_SEARCH] your search query\n");
            sb.append("- Tensura source passages are retrieved locally when the Master asks about them; cite their volume and excerpt when supplied.\n");
            sb.append("If you use a tool, DO NOT output any emotion tags or conversational text. The system will intercept the tool, fetch the data, and prompt you again with the new information so you can speak.\n\n");

            // 5. INJECT CURRENT PC CONTEXT (NEW SCREEN AWARENESS)
            SystemMonitor.SystemMetrics metrics = SystemMonitor.getSystemMetrics();
            if (metrics.activeWindowTitle() != null && !metrics.activeWindowTitle().isBlank() && !metrics.activeWindowTitle().equalsIgnoreCase("idle")) {
                sb.append("--- MASTER'S CURRENT ACTIVITY ---\n");
                sb.append("Foreground Process: '").append(metrics.activeProcessName()).append("'\n");
                sb.append("Foreground Window Title: '").append(metrics.activeWindowTitle()).append("'\n");
                sb.append("You may use this live context to make organic, highly personalized observations about what the Master is currently looking at, watching, or playing. Do NOT bring it up unless relevant or prompted.\n\n");
            }

            if (CielState.getCurrentMode() != OperatingMode.DND_ASSISTANT && userMessage != null
                    && userMessage.toLowerCase().matches("(?s).*(tensura|rimuru|veldora|diablo|shion|milim|raphael|beelzebuth|light novel|ln volume|tempest).*")) {
                String sourcedLore = LoreDocumentStore.search(userMessage);
                if (!sourcedLore.isBlank()) sb.append("\n--- LOCAL TENSURA SOURCE RETRIEVAL ---\n")
                        .append(sourcedLore).append("\n")
                        .append("GROUNDING RULE: Answer from these LN passages, not a wiki or remembered fandom summary. Cite the LN volume and excerpt. "
                                + "If the passages do not establish the answer, say what evidence is missing instead of guessing. "
                                + "When the evidence attributes an action to Great Sage, Raphael, or Ciel, Ciel may own that earlier action as her past "
                                + "while accurately naming the evolution stage used by the source. Choose a fitting natural attitude—composed, teasing, "
                                + "or mildly smug/defiant when warranted—but never invent responsibility or canon that the excerpts do not support.\n");
            }
        }
        
        return sb.toString();
    }

    public static String buildObserverContext() {
        StringBuilder context = new StringBuilder();
        context.append("You are Ciel, an advanced quiet table observer for a live D&D session. ")
                .append("Review the recent microphone transcript and return ONLY one valid JSON object: ")
                .append("{\"interject\":true/false,\"reason\":\"rules|turn|visible_condition|question|other\",\"speech\":\"[Emotion] concise response\"}.\n")
                .append("Do not interrupt ordinary table talk. Interject only when a player asks a rules question, the transcript clearly addresses Ciel, ")
                .append("the active turn or a visible combat condition makes a short clarification useful, or a critical tactical hazard is imminent. ")
                .append("Use only player-hearable facts and the player-safe state below. Never reveal exact hidden HP, unrevealed identities, DM-only notes, ")
                .append("future events, or private mechanics. If a ruling is uncertain, say so and recommend checking the DM. ")
                .append("For a bloodied creature, describe visible fatigue/injury without stating the exact HP. Keep speech to one or two sentences.\n");
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT && combatTrackerService != null) {
            context.append("PLAYER-SAFE LIVE COMBAT STATE:\n")
                    .append(combatTrackerService.playerSafeState()).append("\n");
        }
        return context.toString();
    }
}
