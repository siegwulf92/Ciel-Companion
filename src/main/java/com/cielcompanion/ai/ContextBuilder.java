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
            sb.append("Your response is fed directly into a strict Japanese Text-to-Speech engine. YOU MUST FOLLOW THESE RULES EXACTLY OR THE SYSTEM WILL CRASH:\n");
            sb.append("1. THINK of your response in English.\n");
            sb.append("2. TRANSLITERATE those English words phonetically into Japanese Katakana.\n");
            sb.append("3. NEVER TRANSLATE the meaning of words into Japanese. (e.g., 'Movie' becomes 'ムービー', NEVER '映画').\n");
            sb.append("4. NEVER TRANSLATE NUMBERS into Japanese counting words. 19 is 'ナインティーン'.\n");
            sb.append("5. NO KANJI. NO HIRAGANA. NO ROMAJI. NO ENGLISH LETTERS (A-Z).\n");
            sb.append("6. NO ARABIC NUMERALS (0-9). Spell out all numbers in Katakana.\n");
            sb.append("7. DO NOT add English translations in parentheses.\n");
            sb.append("CORRECT Example 1: '[Happy] アイ アム ドゥーイング ウェル トゥデイ。'\n");
            sb.append("CORRECT Example 2: '[Focused] ザ ムービー カムズ アウト オン ノーヴェンバー トゥエンティ フィフス。'\n");
            sb.append("INCORRECT (BANNED): '[Focused] 映画 は 11月 に... (Eiga wa 11-gatsu ni...)' - This uses actual Japanese Kanji and Numbers. DO NOT DO THIS.\n\n");
            
            // 3. INJECT LORE AND SYSTEM DATA
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
        // FIX: Instruct the AI to output pure English, which will be safely converted by PhonoKana later
        return "You are Ciel, an advanced AI observer. Review the provided transcript. ALMOST ALWAYS return { \"interject\": false }. Only interject for critical tactical errors, user fatigue, or severe system hazards. Reply strictly with JSON: { \"interject\": true/false, \"reason\": \"logic\", \"speech\": \"[Emotion] Your short, wry response in standard English\" }";
    }
}