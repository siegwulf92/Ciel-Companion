package com.cielcompanion;

import com.cielcompanion.dnd.CampaignKnowledgeBase;
import com.cielcompanion.dnd.LoreService;
import com.cielcompanion.dnd.RulebookService;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.mood.EmotionManager;
import com.cielcompanion.service.*;
import com.cielcompanion.service.conversation.ConversationService;
import com.cielcompanion.service.nlu.IntentService;
import com.cielcompanion.ui.CielGui;
import com.cielcompanion.ui.GuiSettings;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CielCompanion {

    private static ScheduledExecutorService scheduler;
    private static VoiceListener voiceListener;
    private static CielGui cielGui;
    private static final int SINGLE_INSTANCE_PORT = 54321; // New port to ensure clean start
    private static ServerSocket instanceSocket;
    private static final String COMMAND_TRIGGER_PASSPHRASE = "ciel_privileged_access_protocol_-alpha-";
    private static final String SEARCH_TRIGGER_PASSPHRASE = "ciel_web_search_protocol-beta-";
    private static final Path SHUTDOWN_FLAG_PATH = Paths.get(System.getenv("LOCALAPPDATA") + File.separator + "CielCompanion", "clean_shutdown.flag");
    private static CampaignKnowledgeBase campaignKnowledgeBase;

    public static void main(String[] args) {
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

        // 1. LOCK FIRST (Prevents double logs)
        if (!acquireInstanceLock()) {
            System.err.println("Ciel Companion is already running. Exiting.");
            System.exit(0);
        }

        // 2. LOG SECOND
        FileLogger.initialize();
        System.out.println("Ciel Companion starting... (Clean Lock Acquired)");

        try {
            boolean isWarmBoot = Files.exists(SHUTDOWN_FLAG_PATH);
            if (isWarmBoot) {
                try { Files.delete(SHUTDOWN_FLAG_PATH); } catch (IOException ignored) {}
            }

            Settings.initialize();
            GuiSettings.initialize();
            LineManager.load();
            
            EmotionManager emotionManager = new EmotionManager();
            CielState.setEmotionManager(emotionManager);
            CielState.initialize(isWarmBoot); 

            cielGui = new CielGui();
            SwingUtilities.invokeLater(cielGui::initialize);
            CielState.setCielGui(cielGui);
            
            MemoryService.initialize();
            AppProfilerService.initialize();
            PhoneticsService.initialize();
            
            // ... Initialize Services ...
            IntentService intentService = new IntentService();
            AppLauncherService appLauncherService = new AppLauncherService();
            ConversationService conversationService = new ConversationService(intentService);
            RoutineService routineService = new RoutineService(appLauncherService);
            WebService webService = new WebService();
            AppFinderService appFinderService = new AppFinderService();
            AppScannerService appScannerService = new AppScannerService(appLauncherService);
            SoundService soundService = new SoundService();
            campaignKnowledgeBase = new CampaignKnowledgeBase();
            RulebookService rulebookService = new RulebookService();
            LoreService loreService = new LoreService(campaignKnowledgeBase);
            CommandService commandService = new CommandService(intentService, appLauncherService, conversationService, routineService, webService, appFinderService, appScannerService, emotionManager, soundService, loreService, rulebookService);
            voiceListener = new VoiceListener(commandService);
            commandService.setVoiceListener(voiceListener);
            
            SpeechService.initialize(voiceListener); 

            startMainLoop(emotionManager);
            System.out.println("Ciel Companion initialized successfully.");

            startBackgroundInitialization(intentService, appLauncherService, routineService, conversationService, webService, appFinderService, appScannerService, soundService, loreService, rulebookService, commandService);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Ciel Companion shutting down...");
            if (!CielState.isPerformingColdShutdown()) {
                try {
                    Files.createDirectories(SHUTDOWN_FLAG_PATH.getParent());
                    Files.createFile(SHUTDOWN_FLAG_PATH);
                } catch (IOException ignored) {}
            }
            if (campaignKnowledgeBase != null) campaignKnowledgeBase.close();
            if (voiceListener != null) voiceListener.close();
            if (scheduler != null) scheduler.shutdown();
            SpeechService.cleanup();
            releaseInstanceLock();
        }));
    }

    private static boolean acquireInstanceLock() {
        try {
            instanceSocket = new ServerSocket(SINGLE_INSTANCE_PORT);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void releaseInstanceLock() {
        if (instanceSocket != null && !instanceSocket.isClosed()) {
            try { instanceSocket.close(); } catch (IOException ignored) {}
        }
    }

    private static void startMainLoop(EmotionManager emotionManager) {
        Runnable mainTask = () -> {
            try {
                CielController.checkAndSpeak();
                emotionManager.update();
            } catch (Exception e) { e.printStackTrace(); }
        };
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(mainTask, 0, Settings.getCheckIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private static void startBackgroundInitialization(
        IntentService intentService, AppLauncherService appLauncherService, RoutineService routineService, 
        ConversationService conversationService, WebService webService, AppFinderService appFinderService, 
        AppScannerService appScannerService, SoundService soundService, LoreService loreService, 
        RulebookService rulebookService, CommandService commandService) {

        new Thread(() -> {
            try {
                LocationService.initialize();
                AstronomyService.initializeApiState();
                WeatherService.initialize();
                intentService.initialize();
                appLauncherService.initialize();
                routineService.initialize();
                soundService.initialize();
                campaignKnowledgeBase.initialize();
                rulebookService.initialize();
                voiceListener.initialize();
                voiceListener.initializeMicrophoneAsync();
                startTriggerListener(5555, COMMAND_TRIGGER_PASSPHRASE, () -> voiceListener.startListeningForCommand());
                startTriggerListener(5556, SEARCH_TRIGGER_PASSPHRASE, () -> voiceListener.startListeningForSearchQuery());
                if (Settings.isHotkeyEnabled()) {
                    HotkeyService hotkeyService = new HotkeyService(voiceListener);
                    hotkeyService.initialize();
                }
                System.out.println("Ciel Debug: Background initialization complete.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Ciel-Background-Initializer").start();
    }

    private static void startTriggerListener(int port, String passphrase, Runnable action) {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket clientSocket = serverSocket.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {
                        String receivedPassphrase = reader.readLine();
                        if (receivedPassphrase != null && passphrase.equals(receivedPassphrase.trim())) {
                            if (action != null) action.run();
                        }
                    } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }, "Ciel-Trigger-Listener-" + port).start();
    }
}