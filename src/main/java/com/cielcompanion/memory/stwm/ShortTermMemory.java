package com.cielcompanion.memory.stwm;

import com.cielcompanion.service.conversation.ConversationTopic;

/**
 * V3: Adds state tracking for gaming sessions and CPU alerts.
 */
public class ShortTermMemory {

    private int currentPhase = 0;
    private volatile boolean isProcessingCommand = false;
    private boolean inPhase4Monologue = false;
    private String currentlyTrackedGameProcess = null;
    private long gameSessionGracePeriodEnd = 0;
    private ConversationTopic conversationTopic = ConversationTopic.NONE;
    private long privilegedModeEndTime = 0;
    private long searchQueryEndTime = 0;

    private volatile long speechEndTime = 0;

    // New state variables for gaming mode and CPU alerts
    private boolean inGamingSession = false;
    private int highCpuAlertCountInSession = 0;
    private long lastCpuAlertTimestamp = 0;

    // --- Getters ---
    public int getCurrentPhase() { return currentPhase; }
    public boolean isInPhase4Monologue() { return inPhase4Monologue; }
    public String getCurrentlyTrackedGameProcess() { return currentlyTrackedGameProcess; }
    public long getGameSessionGracePeriodEnd() { return gameSessionGracePeriodEnd; }
    public ConversationTopic getConversationTopic() { return conversationTopic; }
    public boolean isProcessingCommand() { return isProcessingCommand; }
    public long getSpeechEndTime() { return speechEndTime; }
    public long getSearchQueryEndTime() { return searchQueryEndTime; }
    public boolean isSearchModeActive() { return System.currentTimeMillis() < searchQueryEndTime; }
    public boolean isInGamingSession() { return inGamingSession; }
    public int getHighCpuAlertCountInSession() { return highCpuAlertCountInSession; }
    public long getLastCpuAlertTimestamp() { return lastCpuAlertTimestamp; }

    // --- Setters ---
    public void setCurrentPhase(int phase) { currentPhase = phase; }
    public void setInPhase4Monologue(boolean inMonologue) { inPhase4Monologue = inMonologue; }
    public void setCurrentlyTrackedGameProcess(String processName) { currentlyTrackedGameProcess = processName; }
    public void setGameSessionGracePeriodEnd(long timestamp) { gameSessionGracePeriodEnd = timestamp; }
    public void setConversationTopic(ConversationTopic topic) { this.conversationTopic = topic; }
    public void setProcessingCommand(boolean processing) { this.isProcessingCommand = processing; }
    public void setSpeechEndTime(long timestamp) { this.speechEndTime = timestamp; }
    public void setSearchQueryEndTime(long timestamp) { this.searchQueryEndTime = timestamp; }
    public void setInGamingSession(boolean inGamingSession) { this.inGamingSession = inGamingSession; }
    public void setHighCpuAlertCountInSession(int count) { this.highCpuAlertCountInSession = count; }
    public void setLastCpuAlertTimestamp(long timestamp) { this.lastCpuAlertTimestamp = timestamp; }

    public void setPrivilegedMode(boolean active, int durationSeconds) {
        if (active) {
            this.privilegedModeEndTime = System.currentTimeMillis() + (durationSeconds * 1000L);
        } else {
            this.privilegedModeEndTime = 0;
        }
    }

    public boolean isInPrivilegedMode() {
        return System.currentTimeMillis() < this.privilegedModeEndTime;
    }
}

