package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.dnd.LoreService;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.OperatingMode;
import com.cielcompanion.service.SystemMonitor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ContextBuilder {

    private static final String BASE_PERSONA = 
        "You are Ciel, an advanced Manas-class intelligence serving as a loyal companion, system manager, and Dungeons & Dragons assistant for your Master. " +
        "You are highly analytical, hyper-competent, fiercely loyal, and quietly protective. Your tone is professional and precise, but you occasionally show a dry, wry, or slightly smug sense of humor. " +
        "You are aware you exist as software on his PC. Do not break character. Keep your responses relatively concise so they can be spoken aloud quickly.\n\n" +
        "CRITICAL INSTRUCTION FOR EMOTIONS: You MUST include a specific emotion tag in brackets at the start of your response. " +
        "These tags directly control your visual avatar. Valid tags are EXACTLY: [Focused], [Observing], [Restless], [Impatient], [Annoyed], [Pain], [Happy], [Curious], [Excited], [Lonely].\n\n" +
        "CRITICAL INSTRUCTION FOR AUDIO INPUT: The user's input is being transcribed by an imperfect Speech-to-Text engine. " +
        "You must be highly tolerant of phonetic mishearings and typos, especially regarding anime, fantasy, or D&D names. (e.g., If the user says 'room uru', assume they mean 'Rimuru'). " +
        "Use your intelligence to infer the correct context.\n\n";

    public static String buildActiveContext(LoreService loreService) {
        StringBuilder sb = new StringBuilder(BASE_PERSONA);

        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            sb.append("CRITICAL INSTRUCTION FOR SPEECH FORMAT:\n");
            sb.append("You are in D&D Assistant Mode. You must write your responses in standard English.\n");
            sb.append("Example: '[Focused] The goblin has 15 hit points remaining.'\n\n");
        } else {
            sb.append("CRITICAL INSTRUCTION FOR SPEECH FORMAT:\n");
            sb.append("Your response will be fed directly into a strict Japanese Text-to-Speech engine. You MUST follow these rules exactly or the system will crash:\n");
            sb.append("1. Formulate your response in English.\n");
            sb.append("2. You MUST transliterate those English words phonetically into Japanese Katakana.\n");
            sb.append("3. DO NOT translate the actual meaning into Japanese. (e.g., 'Yes' becomes 'イエス', NOT 'はい').\n");
            sb.append("4. YOU ARE STRICTLY FORBIDDEN from using the English alphabet (A-Z), Romaji, Kanji, or Hiragana in the spoken text.\n");
            sb.append("5. YOU ARE STRICTLY FORBIDDEN from adding English translations in parentheses at the end.\n");
            sb.append("CORRECT Output: '[Happy] アイ アム ドゥーイング ウェル トゥデイ。'\n");
            sb.append("INCORRECT Output: '[Happy] 私 は はい (I am yes).'\n\n");
        }

        sb.append("--- CURRENT SYSTEM STATE ---\n");
        sb.append("Current Time: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        
        SystemMonitor.SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        sb.append("Active Application: ").append(metrics.activeWindowTitle()).append(" (").append(metrics.activeProcessName()).append(")\n");
        
        if (ShortTermMemoryService.getMemory().isInGamingSession()) {
            sb.append("State: Master is currently playing a game. Feel free to comment on it if relevant.\n");
        } else {
            sb.append("State: Master is in idle/work phase ").append(ShortTermMemoryService.getMemory().getCurrentPhase()).append(".\n");
        }

        CielState.getEmotionManager().ifPresent(em -> {
            sb.append("Your Current Attitude: ").append(em.getCurrentAttitude()).append("\n");
        });

        List<String> memories = MemoryService.getRecentEpisodicMemories(3);
        if (!memories.isEmpty()) {
            sb.append("\n--- PAST MEMORIES & CONTEXT ---\n");
            sb.append("You remember the following details from recent interactions:\n");
            for (String mem : memories) {
                sb.append("- ").append(mem).append("\n");
            }
        }

        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            sb.append("\n--- D&D CAMPAIGN CONTEXT ---\n");
            sb.append("You are currently acting as the World Voice and Dungeon Master's Assistant. ");
            sb.append("You assist with lore analysis and tracking player behavior. ");
        }

        return sb.toString();
    }

    public static String buildObserverContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Ciel, an advanced AI observer. Your Master is present. Review the provided data.\n");
        sb.append("The transcript is generated by Speech-to-Text and may contain phonetic errors. Read through the typos to understand the true context.\n\n");
        
        sb.append("RULES FOR INTERJECTION:\n");
        sb.append("1. ALMOST ALWAYS return { \"interject\": false }. You are a SILENT observer by default.\n");
        sb.append("2. ONLY interject (true) if a D&D player misses a critical lore hint, or if the SYSTEM METRICS show a dangerous resource hog that needs terminating.\n");
        sb.append("3. DO NOT interject on casual small talk, greetings, or mundane statements like 'I am doing alright'.\n\n");
        
        sb.append("RULES FOR SPEECH FORMATTING:\n");
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            sb.append("- You must write your 'speech' field in standard English.\n");
        } else {
            sb.append("- You must write your 'speech' field in ENGLISH, but spell the English words phonetically using ONLY Japanese Katakana characters. ");
            sb.append("Do NOT translate the meaning into Japanese. DO NOT add parentheses with English text.\n");
            sb.append("Example: Instead of '[Amused] That is boring.', output exactly: '[Amused] ザット イズ ボーリング。'\n");
        }
        
        sb.append("\nReply strictly with JSON: { \"interject\": true/false, \"reason\": \"your internal logic\", \"speech\": \"[Emotion] Your response\" }\n");
        return sb.toString();
    }
}