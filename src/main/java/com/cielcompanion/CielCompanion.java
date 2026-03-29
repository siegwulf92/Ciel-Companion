package com.cielcompanion;

import com.cielcompanion.dnd.CombatTrackerService;
import com.cielcompanion.dnd.DndCampaignService;
import com.cielcompanion.dnd.LoreService;
import com.cielcompanion.dnd.MasteryService;
import com.cielcompanion.dnd.RulebookService;
import com.cielcompanion.dnd.SpellCheckService;
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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.net.HttpURLConnection;
import java.net.URL;

public class CielCompanion {

    private static ScheduledExecutorService scheduler;
    private static VoiceListener voiceListener;
    private static CielGui cielGui;
    private static final int SINGLE_INSTANCE_PORT = 54321; 
    private static ServerSocket instanceSocket;
    private static final String COMMAND_TRIGGER_PASSPHRASE = "ciel_privileged_access_protocol_-alpha-";
    private static final String SEARCH_TRIGGER_PASSPHRASE = "ciel_web_search_protocol-beta-";
    private static final Path SHUTDOWN_FLAG_PATH = Paths.get(System.getenv("LOCALAPPDATA") + File.separator + "CielCompanion", "clean_shutdown.flag");
    private static Process jarvisProcess;

    public static void main(String[] args) {
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

        if (!acquireInstanceLock()) {
            System.err.println("Ciel Companion is already running. Exiting.");
            System.exit(0);
        }

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
            VaultService.initialize();
            AppProfilerService.initialize();
            PhoneticsService.initialize();
            
            IntentService intentService = new IntentService();
            AppLauncherService appLauncherService = new AppLauncherService();
            ConversationService conversationService = new ConversationService(intentService);
            RoutineService routineService = new RoutineService(appLauncherService);
            WebService webService = new WebService();
            AppFinderService appFinderService = new AppFinderService();
            AppScannerService appScannerService = new AppScannerService(appLauncherService);
            SoundService soundService = new SoundService();
            
            LoreService loreService = new LoreService();
            RulebookService rulebookService = new RulebookService();
            MasteryService masteryService = new MasteryService();
            DndCampaignService dndCampaignService = new DndCampaignService();
            CombatTrackerService combatTrackerService = new CombatTrackerService();
            SpellCheckService spellCheckService = new SpellCheckService(); 
            
            CommandService commandService = new CommandService(
                intentService, appLauncherService, conversationService, routineService, 
                webService, appFinderService, appScannerService, emotionManager, 
                soundService, loreService, rulebookService, 
                masteryService, dndCampaignService,
                combatTrackerService, spellCheckService 
            );

            voiceListener = new VoiceListener(commandService);
            commandService.setVoiceListener(voiceListener);
            
            SpeechService.initialize(voiceListener); 
            SkillCrafterService.initialize();
            HabitTrackerService.initialize();
            com.cielcompanion.ai.SkillEvolutionEngine.initialize();
            com.cielcompanion.memory.stwm.ShortTermMemoryService.initialize();

            startMainLoop(emotionManager);
            System.out.println("Ciel Companion initialized successfully.");

            startBackgroundInitialization(intentService, appLauncherService, routineService, conversationService, webService, appFinderService, appScannerService, soundService, loreService, rulebookService, commandService);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Ciel Companion shutting down...");

            List<String> recentMemories = MemoryService.getRecentEpisodicMemories(5);
            String contextSummary = recentMemories.isEmpty() ? "No significant interactions recorded this session." : String.join("\n- ", recentMemories);
            boolean isReboot = !CielState.isPerformingColdShutdown();
            VaultService.generateSystemDiaryEntryBlocking(contextSummary, isReboot);

            if (isReboot) {
                try {
                    Files.createDirectories(SHUTDOWN_FLAG_PATH.getParent());
                    Files.createFile(SHUTDOWN_FLAG_PATH);
                } catch (IOException ignored) {}
            }
            
            if (voiceListener != null) voiceListener.close();
            if (scheduler != null) scheduler.shutdown();
            SpeechService.cleanup();
            
            if (jarvisProcess != null && jarvisProcess.isAlive()) {
                System.out.println("Ciel Debug: Shutting down background OpenJarvis server...");
                try { Runtime.getRuntime().exec("taskkill /F /T /PID " + jarvisProcess.pid()); } catch (IOException e) { e.printStackTrace(); }
            }
            
            releaseInstanceLock();
            System.out.println("Ciel Companion shutdown complete.");
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
                // ADDED: Wait for Windows to boot Ollama/LM Studio before trying to start OpenJarvis
                waitForInferenceEngines();
                com.cielcompanion.ai.AIEngine.warmUpModels();
                
                // Check and boot the AI Brain
                ensureJarvisServerRunning();

                // RESTORED: These were accidentally deleted during the Swarm boot fixes!
                GameMonitorService.initialize();
                FinanceService.initialize();

                LocationService.initialize();
                AstronomyService.initializeApiState();
                WeatherService.initialize();
                intentService.initialize();
                appLauncherService.initialize();
                routineService.initialize();
                soundService.initialize();
                voiceListener.initialize();
                voiceListener.initializeMicrophoneAsync();
                
                startTriggerListener(5555, COMMAND_TRIGGER_PASSPHRASE, () -> {
                    System.out.println("Ciel Debug: VoiceAttack Command Trigger received. Granting Privileged Mode.");
                    com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().setPrivilegedMode(true, 1);
                    
                    String pendingTask = com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().getPendingSystemTask();
                    
                    if (pendingTask != null) {
                        System.out.println("Ciel Debug: Executing pending system task: " + pendingTask);
                        SpeechService.speakPreformatted("Authorization confirmed. Processing queued system directive.");
                        com.cielcompanion.service.SkillCrafterService.synthesizeNewSkill(pendingTask);
                        com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().clearPendingSystemTask();
                    } else {
                        voiceListener.startListeningForCommand();
                    }
                });
                
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

    private static void ensureJarvisServerRunning() {
        System.out.println("Ciel Debug: Checking for existing OpenJarvis Swarm server on port 8000...");
        
        if (isPortInUse(8000)) {
            System.out.println("Ciel Debug: Stale OpenJarvis instance detected. Sending HTTP shutdown signal...");
            killJarvis();
            
            int killWait = 0;
            while (isPortInUse(8000) && killWait < 3) {
                try { Thread.sleep(1000); } catch (InterruptedException e) {}
                killWait++;
            }
            
            if (isPortInUse(8000)) {
                System.out.println("Ciel Debug: Port 8000 still locked. Executing native process termination...");
                killProcessOnPort(8000);
                
                int forceWait = 0;
                while (isPortInUse(8000) && forceWait < 3) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) {}
                    forceWait++;
                }
            }
        }

        System.out.println("Ciel Debug: Auto-starting local AI Swarm server...");
        try {
            long javaPid = ProcessHandle.current().pid();
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "python openjarvis.py " + javaPid);
            pb.directory(new File("C:\\Ciel Companion\\OpenJarvis-main"));
            pb.redirectErrorStream(true); 
            
            jarvisProcess = pb.start();
            
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jarvisProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[OpenJarvis Swarm] " + line);
                    }
                } catch (Exception e) {
                    System.err.println("Ciel Error: Lost connection to OpenJarvis console stream.");
                }
            }, "Jarvis-Console-Stream").start();
            
            int attempts = 0;
            while (!isJarvisAlive() && attempts < 45) {
                Thread.sleep(1000);
                attempts++;
            }
            
            if (isJarvisAlive()) {
                System.out.println("Ciel Debug: OpenJarvis Swarm successfully auto-started in the background.");
            } else {
                System.err.println("Ciel Error: OpenJarvis failed to respond after 45 seconds. Check the [OpenJarvis Swarm] logs above for errors.");
            }
            
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to auto-launch OpenJarvis process.");
            e.printStackTrace();
        }
    }

    private static boolean isPortInUse(int port) {
        try (Socket ignored = new Socket("localhost", port)) {
            return true; 
        } catch (IOException e) {
            return false; 
        }
    }

    private static void killProcessOnPort(int port) {
        try {
            Process netstat = new ProcessBuilder("cmd.exe", "/c", "netstat -ano | findstr :" + port).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(netstat.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("LISTENING")) {
                        String[] parts = line.trim().split("\\s+");
                        String pid = parts[parts.length - 1];
                        if (!pid.equals("0")) {
                            Process kill = new ProcessBuilder("cmd.exe", "/c", "taskkill /F /T /PID " + pid).start();
                            kill.waitFor();
                            System.out.println("Ciel Debug: Natively terminated ghost process (PID: " + pid + ").");
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Native port kill failed.");
        }
    }

    private static void killJarvis() {
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL("http://localhost:8000/shutdown").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.getResponseCode();
        } catch (Exception e) {}
    }

    private static boolean isJarvisAlive() {
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL("http://localhost:8000/docs").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            int responseCode = connection.getResponseCode();
            return (200 <= responseCode && responseCode <= 399);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean waitForInferenceEngines() {
        System.out.println("Ciel Debug: Waiting for Local Inference Engines (Ollama/LM Studio) to initialize...");
        int attempts = 0;
        while (attempts < 18) { 
            boolean ollamaAlive = pingUrl("http://localhost:11434");
            boolean lmStudioAlive = pingUrl("http://localhost:1234/v1/models");
            
            if (ollamaAlive || lmStudioAlive) {
                System.out.println("Ciel Debug: Local Inference Engine detected! Proceeding with AI boot sequence.");
                return true;
            }
            
            System.out.println("Ciel Debug: Inference engines not ready yet. Waiting 10 seconds... (Attempt " + (attempts + 1) + "/18)");
            try { Thread.sleep(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            attempts++;
        }
        System.err.println("Ciel Error: Inference engines failed to start after 3 minutes. OpenJarvis may fail to load models.");
        return false;
    }

    private static boolean pingUrl(String urlStr) {
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            int responseCode = connection.getResponseCode();
            return (200 <= responseCode && responseCode <= 399);
        } catch (Exception e) {
            return false;
        }
    }

    private static void startTriggerListener(int port, String passphrase, Runnable action) {
        new Thread(() -> {
            boolean bindSuccess = false;
            while (!bindSuccess && !Thread.currentThread().isInterrupted()) {
                try (ServerSocket serverSocket = new ServerSocket(port)) {
                    System.out.println("Ciel Debug: Trigger listener started on port " + port);
                    bindSuccess = true;
                    
                    while (!Thread.currentThread().isInterrupted()) {
                        try (Socket clientSocket = serverSocket.accept();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {
                            
                            char[] buffer = new char[256];
                            int charsRead = reader.read(buffer);
                            
                            if (charsRead > 0) {
                                String receivedPassphrase = new String(buffer, 0, charsRead).trim();
                                if (passphrase.equals(receivedPassphrase)) {
                                    if (action != null) action.run();
                                }
                            }
                        } catch (IOException e) {
                            System.err.println("Ciel Warning: Trigger connection error: " + e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Ciel Error: Could not bind trigger port " + port + ". Retrying in 5 seconds...");
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "Ciel-Trigger-Listener-" + port).start();
    }
}