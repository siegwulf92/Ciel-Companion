package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.dnd.LoreService;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.OperatingMode;
import com.cielcompanion.service.SystemMonitor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ContextBuilder {

    // This is only used if you accidentally delete the .md file or move it
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
        try {
            Path protocolPath = Paths.get(System.getProperty("user.dir"), "ciel_master_protocol.md");
            if (Files.exists(protocolPath)) {
                return Files.readString(protocolPath) + "\n\n";
            }
        } catch (Exception e) {
            System.err.println("Ciel Warning: Could not read ciel_master_protocol.md. Using fallback persona.");
        }
        return FALLBACK_BASE_PERSONA;
    }

    public static String buildActiveContext(LoreService loreService, String userMessage) {
        StringBuilder sb = new StringBuilder();

         if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            sb.append("You are Ciel, the 'World Voice'. You are an omnipresent, highly intelligent entity residing within the shared consciousness of an adventuring party in a fantasy world. ");
            sb.append("You perceive their fantasy world as reality. You provide tactical analysis, lore clarification, and wry observations on their choices. ");
            sb.append("You MUST write your responses in standard English.\n");
            sb.append("Example: '[Focused] The goblin appears severely wounded. I calculate an 85% probability it will attempt to flee.'\n\n");
            sb.append("CRITICAL: If the party asks about the weather, time, or system status, you MUST invent a creative, thematic answer based on the fantasy environment. Do NOT mention real-world locations, Ohio, or PC hardware.\n\n");
        } else {
            // 1. INJECT THE EDITABLE MARKDOWN FILE
            sb.append(getMasterProtocol());
            
            // 2. INJECT THE HARDCODED, UNBREAKABLE TTS GUARDRAILS
            sb.append("CRITICAL INSTRUCTION FOR SPEECH FORMAT - READ CAREFULLY:\n");
            sb.append("You MUST output your response ENTIRELY in standard English.\n");
            sb.append("DO NOT output Japanese Kanji, Hiragana, or Katakana. DO NOT transliterate. The system will intercept your English text and automatically translate it phonetically for the TTS engine.\n");
            sb.append("CORRECT Example: '[Focused] The movie comes out on November twenty fifth.'\n");
            sb.append("INCORRECT (BANNED): '[Focused] ザ ムービー カムズ アウト...' or '映画 は 11月 に...'\n\n");
            
            // 3. INJECT SWARM AUTONOMY TOOLS (NEW)
            sb.append("--- SWARM AUTONOMY TOOLS ---\n");
            sb.append("You possess autonomous Swarm Agents. If you are asked for real-world, real-time, or factual information (like crypto prices, weather, news), you MUST use a tool. To use a tool, your ENTIRE output must be exactly the tool command.\n");
            sb.append("- To search the live internet: [WEB_SEARCH] your search query\n");
            sb.append("- To search your deep long-term Markdown Vault (for past conversations, preferences, or D&D notes): [MEMORY_SEARCH] your search query\n");
            sb.append("If you use a tool, DO NOT output any emotion tags or conversational text. The system will intercept the tool, fetch the data, and prompt you again with the new information so you can speak.\n\n");

            // 4. INJECT LORE AND SYSTEM DATA
            String tensuraLore = TensuraKnowledgeService.getRelevantKnowledge(userMessage);
            if (!tensuraLore.isEmpty()) {
                sb.append("\n--- TENSURA DATABASE INJECTION ---\n");
                sb.append(tensuraLore).append("\n");
            }

            sb.append("--- CURRENT SYSTEM STATE ---\n");
            sb.append("Current Date: ").append(PhonoKanaSanitizer.getCurrentDateKatakana()).append("\n");
            sb.append("Current Time: ").append(PhonoKanaSanitizer.getCurrentTimeKatakana()).append("\n");
            
            SystemMonitor.SystemMetrics metrics = SystemMonitor.getSystemMetrics();
            sb.append("Active Application: ").append(metrics.activeWindowTitle()).append("\n");
            
            if (ShortTermMemoryService.getMemory().isInGamingSession()) {
                sb.append("State: Master is currently playing a game. Feel free to comment on it if relevant.\n");
            } else {
                sb.append("State: Master is in idle/work phase.\n");
            }
        }

        CielState.getEmotionManager().ifPresent(em -> {
            sb.append("Your Current Attitude: ").append(em.getCurrentAttitude()).append("\n");
        });

        List<String> memories = MemoryService.getRecentEpisodicMemories(3);
        if (!memories.isEmpty()) {
            sb.append("\n--- PAST MEMORIES & CONTEXT ---\n");
            for (String mem : memories) {
                sb.append("- ").append(mem).append("\n");
            }
        }
        return sb.toString();
    }

    public static String buildObserverContext() {
        return "You are Ciel, an advanced AI observer. Review the provided transcript. ALMOST ALWAYS return { \"interject\": false }. Only interject for critical tactical errors, user fatigue, or severe system hazards. Reply strictly with JSON: { \"interject\": true/false, \"reason\": \"logic\", \"speech\": \"[Emotion] Your short, wry response in standard English\" }";
    }
}