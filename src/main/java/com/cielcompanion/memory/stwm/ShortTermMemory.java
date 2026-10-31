package com.cielcompanion.memory.stwm;

import com.cielcompanion.service.conversation.ConversationTopic;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * V3: Adds state tracking for gaming sessions, CPU alerts, and Pending Skills.
 */
public class ShortTermMemory {

    private volatile int currentPhase = 0;
    private volatile boolean isProcessingCommand = false;
    private volatile boolean inPhase4Monologue = false;
    private volatile String currentlyTrackedGameProcess = null;
    private volatile long gameSessionGracePeriodEnd = 0;
    private volatile ConversationTopic conversationTopic = ConversationTopic.NONE;
    private volatile long privilegedModeEndTime = 0;
    private volatile long searchQueryEndTime = 0;

    private volatile long speechEndTime = 0;

    // New state variables for gaming mode and CPU alerts
    private volatile boolean inGamingSession = false;
    private volatile int highCpuAlertCountInSession = 0;
    private volatile long lastCpuAlertTimestamp = 0;

    // NEW: Holds unauthenticated skill creation requests
    private volatile String pendingSystemTask = null;

    // NEW: Session text context for AI ingestion
    private final List<String> sessionContext = new CopyOnWriteArrayList<>();

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
    
    public String getPendingSystemTask() { return pendingSystemTask; }
    public List<String> getSessionContext() { return sessionContext; }

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
    
    public void setPendingSystemTask(String task) { this.pendingSystemTask = task; }
    public void clearPendingSystemTask() { this.pendingSystemTask = null; }

    // Adds a text event to her volatile working memory
    public void addContext(String context) {
        sessionContext.add(context);
        if (sessionContext.size() > 50) { // Prevent memory leaks over long uptimes
            sessionContext.remove(0);
        }
    }

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
