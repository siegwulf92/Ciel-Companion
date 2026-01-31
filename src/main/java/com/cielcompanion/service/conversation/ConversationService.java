package com.cielcompanion.service.conversation;

import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.LineManager;
import com.cielcompanion.service.nlu.CommandAnalysis;
import com.cielcompanion.service.nlu.Intent;
import com.cielcompanion.service.nlu.IntentService;

import java.util.Map;

/**
 * Manages the state and flow of a multi-turn conversation.
 */
public class ConversationService {
    
    private final IntentService intentService;

    public ConversationService(IntentService intentService) {
        this.intentService = intentService;
    }


    /**
     * Checks if the user's input is a follow-up to a previous command.
     * @param text The user's transcribed speech.
     * @return A CommandAnalysis if it's a valid follow-up, otherwise null.
     */
    public CommandAnalysis checkForFollowUp(String text) {
        ConversationTopic currentTopic = ShortTermMemoryService.getMemory().getConversationTopic();
        if (currentTopic == ConversationTopic.NONE) {
            return null;
        }

        String lowerText = text.toLowerCase();

        // Handle follow-ups for system status
        if (currentTopic == ConversationTopic.SYSTEM_STATUS) {
            if (lowerText.contains("memory") || lowerText.contains("ram")) {
                return new CommandAnalysis(Intent.GET_TOP_MEMORY_PROCESS, Map.of());
            }
            if (lowerText.contains("cpu") || lowerText.contains("processor")) {
                return new CommandAnalysis(Intent.GET_TOP_CPU_PROCESS, Map.of());
            }
        }
        
        // CORRECTED: Logic to handle weather-related follow-up questions.
        if (currentTopic == ConversationTopic.WEATHER) {
            if (lowerText.contains("tomorrow") || lowerText.contains("what about tomorrow")) {
                // If the user asks about tomorrow, interpret it as a forecast request.
                return new CommandAnalysis(Intent.GET_WEATHER_FORECAST, Map.of());
            }
        }

        // If no follow-up is detected, reset the topic
        ShortTermMemoryService.getMemory().setConversationTopic(ConversationTopic.NONE);
        return null;
    }

    /**
     * Updates the current conversation topic based on the user's intent.
     * @param analysis The result of the intent analysis.
     */
    public void updateConversationTopic(CommandAnalysis analysis) {
        ConversationTopic newTopic = ConversationTopic.fromIntent(analysis.intent());
        if (newTopic != ShortTermMemoryService.getMemory().getConversationTopic()) {
            System.out.println("Ciel Debug: Conversation topic changed from " + ShortTermMemoryService.getMemory().getConversationTopic() + " to " + newTopic);
            ShortTermMemoryService.getMemory().setConversationTopic(newTopic);
        }
    }
}

