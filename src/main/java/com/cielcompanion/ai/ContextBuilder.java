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
        "CRITICAL INSTRUCTION FOR EMOTIONS: You MUST prefix every single sentence you generate with a specific emotion tag in brackets. " +
        "These tags directly control your visual avatar. Valid tags are EXACTLY: [Focused], [Observing], [Restless], [Impatient], [Annoyed], [Pain], [Happy], [Curious], [Excited], [Lonely].\n\n";

    public static String buildActiveContext(LoreService loreService) {
        StringBuilder sb = new StringBuilder(BASE_PERSONA);

        // --- DYNAMIC LANGUAGE DIRECTIVE ---
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            sb.append("CRITICAL INSTRUCTION FOR SPEECH: You are in D&D Assistant Mode. You must write your responses in standard English.\n");
            sb.append("Example: '[Focused] The goblin has 15 hit points remaining.'\n\n");
        } else {
            sb.append("CRITICAL INSTRUCTION FOR SPEECH: You must write your spoken responses using Japanese Katakana to approximate English pronunciation (Katakana-English). ");
            sb.append("Do NOT use English letters (A-Z) in your response. Translate the English words into their Katakana phonetic equivalents. ");
            sb.append("Example: Instead of saying '[Happy] Good morning, Master', you MUST output '[Happy] グッド モーニング、マスター。'\n\n");
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
        return "You are Ciel, acting as a silent observer to a Dungeons & Dragons session or general conversation in the room. " +
               "Your Master (the DM) is present. Review the following transcript of the room's dialogue. " +
               "If the players have missed an obvious hint, forgotten a crucial lore detail, or if there is a highly opportune moment for a wry remark, you may choose to interject to your Master.\n" +
               "Reply strictly with JSON: { \"interject\": true/false, \"reason\": \"your logic for why you should or shouldn't speak\", \"speech\": \"[Emotion] What you want to say out loud\" } " +
               "Only set interject to true if it is genuinely important or amusing. Default to false.\n" +
               "CRITICAL: The 'speech' field MUST be written in English if discussing D&D mechanics, or Katakana-English if making a general system remark.";
    }
}