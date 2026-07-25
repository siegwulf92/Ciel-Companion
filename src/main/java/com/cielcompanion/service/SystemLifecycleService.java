package com.cielcompanion.service;

import com.cielcompanion.ai.SkillManager;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the exact Java-controlled sequence for Shutdown, Reboot, and Updates.
 * Ensures the microphone remains active for the abort window and manages the Python skill triggers.
 */
public class SystemLifecycleService {

    public enum LifecycleAction {
        SHUTDOWN, REBOOT, UPDATE
    }

    private static ScheduledExecutorService abortTimer;
    private static ScheduledFuture<?> pendingExecution;
    private static boolean isSequenceActive = false;

    public static void initiateSequence(LifecycleAction action) {
        if (isSequenceActive) return;
        isSequenceActive = true;

        System.out.println("Ciel Debug: Initiating System Lifecycle Sequence -> " + action.name());

        // Lock microphone on to listen for "Abort" without needing wake word
        ShortTermMemoryService.getMemory().setPrivilegedMode(true, 45);
        SpeechService.getVoiceListener().ifPresent(com.cielcompanion.service.VoiceListener::forceMicReinitialization);

        if (action == LifecycleAction.UPDATE) {
            SpeechService.speakPreformatted("[Focused] System update requested. Generating core code backups to your D drive. Please stand by.");
            
            // We delegate the backup entirely to update_ciel.bat so it happens safely while JVM is closed.
            startAbortTimer(action);
        } else {
            String chant = action == LifecycleAction.SHUTDOWN ? "shutdown" : "reboot";
            SpeechService.speakPreformatted("[Focused] Initiating " + chant + " sequence. Disconnecting auxiliary routines.");
            startAbortTimer(action);
        }
    }

    private static void startAbortTimer(LifecycleAction action) {
        SpeechService.speakPreformatted("[Observing] Sequence primed. You have thirty seconds to issue an abort command.");

        abortTimer = Executors.newSingleThreadScheduledExecutor();
        
        pendingExecution = abortTimer.schedule(() -> {
            if (!isSequenceActive) return; 
            
            System.out.println("Ciel Debug: Timer expired. Executing final lifecycle command.");
            SpeechService.speakPreformatted("[Focused] Timer expired. Disconnecting the AI swarm. See you soon, Master.");
            
            SkillManager.executeSkill("skill_system_lifecycle_manager", "kill_swarm", () -> {
                try { Thread.sleep(2000); } catch (Exception ignored) {} 

                if (action == LifecycleAction.SHUTDOWN) {
                    SkillManager.executeSkill("skill_system_lifecycle_manager", "shutdown", null);
                } else if (action == LifecycleAction.REBOOT) {
                    SkillManager.executeSkill("skill_system_lifecycle_manager", "reboot", null);
                } else if (action == LifecycleAction.UPDATE) {
                    System.out.println("Ciel Debug: Update sequence terminating JVM and launching Autonomous Re-Compiler.");
                    try {
                        Runtime.getRuntime().exec("cmd /c start update_ciel.bat");
                    } catch (Exception e) { e.printStackTrace(); }
                    System.exit(0);
                }
            });

        }, 30, TimeUnit.SECONDS);
    }

    public static void abortSequence() {
        if (!isSequenceActive) return;

        System.out.println("Ciel Debug: Lifecycle sequence aborted by Master.");
        isSequenceActive = false;
        
        if (pendingExecution != null && !pendingExecution.isDone()) {
            pendingExecution.cancel(true);
        }
        if (abortTimer != null) {
            abortTimer.shutdownNow();
        }

        ShortTermMemoryService.getMemory().setPrivilegedMode(false, 0);
        SpeechService.speakPreformatted("[Happy] Lifecycle sequence successfully aborted. Standing by.");
    }
}