package com.cielcompanion.service.conversation;

import com.cielcompanion.service.nlu.Intent;

/**
 * Represents the current topic of conversation to allow for follow-up questions.
 */
public enum ConversationTopic {
    NONE,
    WEATHER,
    ASTRONOMY,
    SYSTEM_STATUS;

    /**
     * Maps an NLU intent to a conversation topic.
     * @param intent The intent to map.
     * @return The corresponding ConversationTopic.
     */
    public static ConversationTopic fromIntent(Intent intent) {
        return switch (intent) {
            case GET_WEATHER -> WEATHER;
            case GET_DAILY_REPORT -> ASTRONOMY;
            case GET_SYSTEM_STATUS -> SYSTEM_STATUS;
            default -> NONE;
        };
    }
}

