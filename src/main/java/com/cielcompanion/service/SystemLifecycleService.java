package com.cielcompanion.service;

import com.cielcompanion.ai.SkillManager;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.CielCompanion;

import java.util.List;
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

        String chantKey;
        String chantText;
        if (action == LifecycleAction.UPDATE) {
            chantKey = "sys_update_ack";
            chantText = "[Focused] System update requested. Generating core code backups. Please stand by.";
        } else if (action == LifecycleAction.SHUTDOWN) {
            chantKey = "sys_shutdown_ack";
            chantText = "[Focused] Initiating shutdown sequence. Disconnecting auxiliary routines.";
        } else {
            chantKey = "sys_reboot_ack";
            chantText = "[Focused] Initiating reboot sequence. Disconnecting auxiliary routines.";
        }

        // STEP 1: Speak acknowledgment (uses cached audio file if available)
        SpeechService.speakSequentially(List.of(new DialogueLine(chantKey, chantText)), 0, true, () -> {
            if (!isSequenceActive) return;
            
            // STEP 2: Save Memory / Diary
            System.out.println("Ciel Debug: Acknowledgment complete. Saving memory vault...");
            VaultService.generateSystemDiaryEntryBlocking("System lifecycle action requested: " + action.name(), action == LifecycleAction.REBOOT);

            if (!isSequenceActive) return;

            // STEP 3: Speak 30s Warning
            String warnKey = "sys_lifecycle_warn";
            String warnText = "[Observing] Sequence primed. You have thirty seconds to issue an abort command.";
            
            SpeechService.speakSequentially(List.of(new DialogueLine(warnKey, warnText)), 0, true, () -> {
                // STEP 4: Start timers
                startAbortTimer(action);
            });
        });
    }

    private static void startAbortTimer(LifecycleAction action) {
        if (!isSequenceActive) return;
        System.out.println("Ciel Debug: 30-second system termination timer started.");
        abortTimer = Executors.newSingleThreadScheduledExecutor();
        
        // At 25s: Kill Swarm
        abortTimer.schedule(() -> {
            if (!isSequenceActive) return;
            System.out.println("Ciel Debug: 25s mark reached. Terminating AI Swarm processes.");
            SkillManager.executeSkill("skill_system_lifecycle_manager", "kill_swarm", null);
            CielCompanion.killJarvis();
        }, 25, TimeUnit.SECONDS);

        // At 30s: Execute OS Action
        pendingExecution = abortTimer.schedule(() -> {
            if (!isSequenceActive) return; 
            
            System.out.println("Ciel Debug: 30s timer expired. Executing final lifecycle command.");
            
            if (action == LifecycleAction.SHUTDOWN) {
                SkillManager.executeSkill("skill_system_lifecycle_manager", "shutdown", null);
            } else if (action == LifecycleAction.REBOOT) {
                SkillManager.executeSkill("skill_system_lifecycle_manager", "reboot", null);
            } else if (action == LifecycleAction.UPDATE) {
                System.out.println("Ciel Debug: Update sequence terminating JVM and launching Autonomous Re-Compiler.");
                try {
                    Runtime.getRuntime().exec("cmd /c start update_ciel.bat");
                } catch (Exception e) { e.printStackTrace(); }
            }
            System.exit(0);
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
        SpeechService.speakPreformatted("[Happy] Lifecycle sequence successfully aborted. Standing by.", "sys_lifecycle_abort");
    }
}