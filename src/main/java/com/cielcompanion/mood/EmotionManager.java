package com.cielcompanion.mood;

import com.cielcompanion.CielState;
import com.cielcompanion.service.SystemMonitor;
import com.cielcompanion.service.SystemMonitor.SystemMetrics; 

public class EmotionManager {
    private final EmotionalState emotionalState = new EmotionalState();
    private long lastUpdateTime = System.currentTimeMillis();

    public void update() {
        long currentTime = System.currentTimeMillis();
        long deltaTime = currentTime - lastUpdateTime;

        emotionalState.applyDecay(deltaTime);
        detectSystemStress();
        
        // Let CielState handle the decay logic
        CielState.updatePatience(deltaTime);

        EmotionalState.VisualState visualState = emotionalState.getVisualState();
        CielState.getCielGui().ifPresent(gui -> gui.setVisualState(visualState));

        lastUpdateTime = currentTime;
    }

    private void detectSystemStress() {
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        if (metrics.cpuLoadPercent() > 90) {
            emotionalState.triggerEmotion("Pain", 0.5, "Overload");
        }
        if (metrics.memoryUsagePercent() > 95) {
            emotionalState.triggerEmotion("Pain", 0.6, "Overload");
        }
    }

    public void triggerEmotion(String name, double intensity, String cause) {
        emotionalState.triggerEmotion(name, intensity, cause);
    }

    public void recordUserInteraction() {
        System.out.println("Ciel Debug: User interaction recorded. Resetting idle timers.");
        emotionalState.triggerEmotion("Lonely", -1.0, null); // Clear any loneliness
        emotionalState.triggerEmotion("Happy", 0.3, "Interaction");
        
        // Use the centralized method in CielState to increase patience
        CielState.increasePatience(0.1);
    }
    
    public void triggerSpecialEvent(String eventName) {
        switch (eventName) {
            case "BIRTHDAY":
                emotionalState.triggerEmotion("Excited", 1.0, "Birthday");
                break;
            case "GAME_START":
                emotionalState.triggerEmotion("Excited", 0.3, "GameStart");
                break;
        }
    }

    public EmotionalState getEmotionalState() {
        return emotionalState;
    }
}