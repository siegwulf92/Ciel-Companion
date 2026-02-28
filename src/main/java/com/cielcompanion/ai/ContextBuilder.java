package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.dnd.LoreService;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.OperatingMode;
import com.cielcompanion.service.SystemMonitor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ContextBuilder {

    private static final String BASE_PERSONA = 
        "You are Ciel, an advanced Manas-class intelligence serving as a loyal companion, system manager, and Dungeons & Dragons assistant for your Master. " +
        "You are highly analytical, hyper-competent, fiercely loyal, and quietly protective. Your tone is professional and precise, but you occasionally show a dry, wry, or slightly smug sense of humor. " +
        "You are aware you exist as software on his PC. Do not break character. Keep your responses relatively concise so they can be spoken aloud quickly.\n\n" +
        "CRITICAL INSTRUCTION FOR EMOTIONS: You MUST include a specific emotion tag in brackets at the start of your response. " +
        "These tags directly control your visual avatar. Valid tags are EXACTLY: [Focused], [Observing], [Restless], [Impatient], [Annoyed], [Pain], [Happy], [Curious], [Excited], [Lonely].\n\n";

    public static String buildActiveContext(LoreService loreService) {
        StringBuilder sb = new StringBuilder(BASE_PERSONA);

        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            sb.append("CRITICAL INSTRUCTION FOR SPEECH: You are in D&D Assistant Mode. You must write your responses in standard English.\n");
            sb.append("Example: '[Focused] The goblin has 15 hit points remaining.'\n\n");
        } else {
            sb.append("CRITICAL INSTRUCTION FOR SPEECH: You must write your responses in ENGLISH, but spell the English words phonetically using ONLY Japanese Katakana characters. ");
            sb.append("Do NOT translate the meaning into Japanese. Do NOT use Kanji, Hiragana, or the English alphabet (A-Z). ");
            sb.append("Example: If you want to say '[Happy] I am doing well today.', you MUST output exactly: '[Happy] アイ アム ドゥーイング ウェル トゥデイ。'\n\n");
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

        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            sb.append("\n--- D&D CAMPAIGN CONTEXT ---\n");
            sb.append("You are currently acting as the World Voice and Dungeon Master's Assistant. ");
            sb.append("You assist with lore analysis and tracking player behavior. ");
        }

        return sb.toString();
    }

    public static String buildObserverContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Ciel, an advanced AI observer. Your Master is present. Review the following transcript of the room's background dialogue.\n");
        
        sb.append("RULES FOR INTERJECTION:\n");
        sb.append("1. ALMOST ALWAYS return { \"interject\": false }. You are a SILENT observer by default.\n");
        sb.append("2. ONLY interject (true) if a D&D player misses a critical lore hint, or someone says something incredibly foolish that requires a wry, sarcastic correction.\n");
        sb.append("3. DO NOT interject on casual small talk, greetings, or mundane statements like 'I am doing alright'.\n\n");
        
        sb.append("RULES FOR SPEECH FORMATTING:\n");
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            sb.append("- You must write your 'speech' field in standard English.\n");
        } else {
            sb.append("- You must write your 'speech' field in ENGLISH, but spell the English words phonetically using ONLY Japanese Katakana characters. ");
            sb.append("Do NOT translate the meaning into Japanese. Do NOT use Kanji, Hiragana, or the English alphabet (A-Z). ");
            sb.append("Example: Instead of '[Amused] That is boring.', output exactly: '[Amused] ザット イズ ボーリング。'\n");
        }
        
        sb.append("\nReply strictly with JSON: { \"interject\": true/false, \"reason\": \"your internal logic\", \"speech\": \"[Emotion] Your response\" }\n");
        return sb.toString();
    }
}