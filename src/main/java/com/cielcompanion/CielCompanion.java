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
import com.cielcompanion.util.ProjectPaths;

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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.net.HttpURLConnection;
import java.net.URL;

public class CielCompanion {

    private static ScheduledExecutorService scheduler;
    private static VoiceListener voiceListener;
    private static CielGui cielGui;
    private static final Path INSTANCE_LOCK_PATH = Paths.get(
            System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")),
            "CielCompanion", "ciel-instance.lock");
    private static FileChannel instanceLockChannel;
    private static FileLock instanceFileLock;
    private static final String COMMAND_TRIGGER_PASSPHRASE = localTriggerKey("command");
    private static final String SEARCH_TRIGGER_PASSPHRASE = localTriggerKey("search");
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
        boolean updateBoot = reportUpdaterStatus();
        
        try {
            Class.forName("org.sqlite.JDBC");
            System.out.println("Ciel Debug: SQLite Driver pre-loaded on main thread.");
        } catch (ClassNotFoundException e) {
            System.err.println("Ciel FATAL Error: SQLite Driver not found in classpath.");
        }

        try {
            boolean isWarmBoot = Files.exists(SHUTDOWN_FLAG_PATH);
            if (isWarmBoot) {
                try { Files.delete(SHUTDOWN_FLAG_PATH); } catch (IOException ignored) {}
            }

            Settings.initialize();
            DeviceProfileService.initialize();
            GuiSettings.initialize();
            LineManager.load();
            voiceListener = new VoiceListener(null);
            if (Settings.isHotkeyEnabled()) new HotkeyService(voiceListener).initialize();
            
            EmotionManager emotionManager = new EmotionManager();
            CielState.setEmotionManager(emotionManager);
            CielState.initialize(isWarmBoot, updateBoot); 

            cielGui = new CielGui();
            SwingUtilities.invokeLater(cielGui::initialize);
            CielState.setCielGui(cielGui);
            
            SpeechService.initialize(voiceListener);
            HabitTrackerService.initialize();
            GameMonitorService.initialize();
            startMainLoop(emotionManager);
            
            System.out.println("Ciel Debug: Priority Speech & Tracking systems initialized. Proceeding with heavy loads.");
            
            CompletableFuture.runAsync(() -> {
                try {
                    MemoryService.initialize();
                    com.cielcompanion.service.CodexAgentService.initialize();
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

                    voiceListener.setCommandService(commandService);
                    commandService.setVoiceListener(voiceListener);
                    
                    SpeechService.initialize(voiceListener); 
                    SkillCrafterService.initialize();
                    com.cielcompanion.ai.LoreAnalyzerService.initialize();
                    com.cielcompanion.ai.SkillEvolutionEngine.initialize();
                    com.cielcompanion.memory.stwm.ShortTermMemoryService.initialize();

                    System.out.println("Ciel Companion initialized successfully.");

                    startBackgroundInitialization(intentService, appLauncherService, routineService, conversationService, webService, appFinderService, appScannerService, soundService, loreService, rulebookService, commandService);
                } catch (Exception e) {
                    System.err.println("Ciel Error during heavy async boot.");
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Ciel Companion shutting down...");

            List<String> recentMemories = MemoryService.getRecentEpisodicMemories(50);
            String contextSummary = recentMemories.isEmpty() ? "No significant interactions recorded this session." : String.join("\n- ", recentMemories);
            boolean isReboot = !CielState.isPerformingColdShutdown();
            
            if (!CielState.isDiaryWrittenThisSession()) {
                VaultService.generateSystemDiaryEntryBlocking(contextSummary, isReboot);
                CielState.setDiaryWrittenThisSession(true);
            } else {
                System.out.println("Ciel Debug: Final diary already saved by the lifecycle command; skipping duplicate shutdown-hook entry.");
            }

            if (isReboot) {
                try {
                    Files.createDirectories(SHUTDOWN_FLAG_PATH.getParent());
                    Files.createFile(SHUTDOWN_FLAG_PATH);
                } catch (IOException ignored) {}
            }
            
            if (voiceListener != null) voiceListener.close();
            if (scheduler != null) scheduler.shutdown();
            SpeechService.cleanup();
            
            System.out.println("Ciel Debug: Initiating graceful Swarm shutdown (VRAM Purge)...");
            killJarvis();
            
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {} 
            
            if (jarvisProcess != null && jarvisProcess.isAlive()) {
                System.out.println("Ciel Debug: Swarm process lingering. Executing native JVM forceful termination.");
                jarvisProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                jarvisProcess.destroyForcibly();
            }
            
            releaseInstanceLock();
            System.out.println("Ciel Companion shutdown complete.");
        }));
    }

    private static synchronized String localTriggerKey(String name) {
        Path file = ProjectPaths.root().resolve(".local/trigger-keys.properties");
        java.util.Properties keys = new java.util.Properties();
        try {
            if (Files.isRegularFile(file)) {
                try (var input = Files.newInputStream(file)) { keys.load(input); }
            }
            String key = keys.getProperty(name);
            if (key == null) {
                byte[] bytes = new byte[32];
                new java.security.SecureRandom().nextBytes(bytes);
                key = java.util.HexFormat.of().formatHex(bytes);
                keys.setProperty(name, key);
                Files.createDirectories(file.getParent());
                Path staging = file.resolveSibling("trigger-keys.properties.tmp");
                try (var output = Files.newOutputStream(staging)) {
                    keys.store(output, "Device-local trigger keys; never commit or share");
                }
                Files.move(staging, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // Preserve existing local VoiceAttack keys during migration.
            // Newly generated keys above remain 32 random bytes (64 hex characters).
            if (key.length() < 24 || key.contains("\n") || key.contains("\r"))
                throw new IllegalStateException("Invalid device-local trigger key: " + name);
            return key;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load device-local trigger keys", error);
        }
    }

    private static boolean acquireInstanceLock() {
        try {
            Files.createDirectories(INSTANCE_LOCK_PATH.getParent());
            instanceLockChannel = FileChannel.open(INSTANCE_LOCK_PATH,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceFileLock = instanceLockChannel.tryLock();
            if (instanceFileLock != null) return true;
            instanceLockChannel.close();
            instanceLockChannel = null;
            System.err.println("Ciel instance lock is already held by another process.");
            return false;
        } catch (OverlappingFileLockException e) {
            closeInstanceLockChannel();
            System.err.println("Ciel instance lock is already held in this process.");
            return false;
        } catch (IOException e) {
            closeInstanceLockChannel();
            System.err.println("Could not acquire Ciel instance lock at " + INSTANCE_LOCK_PATH + ": " + e);
            return false;
        }
    }

    private static void releaseInstanceLock() {
        try { if (instanceFileLock != null && instanceFileLock.isValid()) instanceFileLock.release(); }
        catch (IOException e) { System.err.println("Could not release Ciel instance lock: " + e); }
        instanceFileLock = null;
        closeInstanceLockChannel();
    }

    private static void closeInstanceLockChannel() {
        try { if (instanceLockChannel != null && instanceLockChannel.isOpen()) instanceLockChannel.close(); }
        catch (IOException e) { System.err.println("Could not close Ciel instance lock channel: " + e); }
        instanceLockChannel = null;
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
        // REMOVED: keepLocalModelsAlive() scheduler which was causing the Maven Error!
    }

    private static void startBackgroundInitialization(
        IntentService intentService, AppLauncherService appLauncherService, RoutineService routineService, 
        ConversationService conversationService, WebService webService, AppFinderService appScannerService, 
        AppScannerService appScannerService2, SoundService soundService, LoreService loreService, 
        RulebookService rulebookService, CommandService commandService) {

        new Thread(() -> {
            try {
                CompletableFuture.runAsync(() -> {
                    ensureJarvisServerRunning();
                    if (waitForInferenceEngines()) com.cielcompanion.ai.AIEngine.warmUpModels();
                });

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
                
                // VoiceAttack mapping: 5555 is the search trigger; 5556 is the
                // authenticated command trigger that unlocks privileged actions.
                startTriggerListener(5555, SEARCH_TRIGGER_PASSPHRASE, () -> voiceListener.startListeningForSearchQuery());
                startTriggerListener(5556, COMMAND_TRIGGER_PASSPHRASE, () -> {
                    System.out.println("Ciel Debug: VoiceAttack Command Trigger received. Granting Privileged Mode.");
                    if (voiceListener != null) {
                        System.out.println("Ciel Debug: Forcing Watchdog Mic Reinitialization for VoiceAttack Trigger...");
                        voiceListener.forceMicReinitialization();
                    }
                    com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().setPrivilegedMode(true, 60);
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
            String python = ProjectPaths.pythonExecutable();
            ProcessBuilder pb = new ProcessBuilder(python,
                    ProjectPaths.root().resolve("OpenJarvis-main/openjarvis.py").toString(), Long.toString(javaPid));
            pb.directory(ProjectPaths.root().resolve("OpenJarvis-main").toFile());
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
            while (!isJarvisAlive() && attempts < 120 && jarvisProcess.isAlive()) {
                Thread.sleep(1000);
                attempts++;
            }
            
            if (isJarvisAlive()) {
                System.out.println("Ciel Debug: OpenJarvis Swarm successfully auto-started in the background.");
            } else {
                System.err.println("Ciel Error: OpenJarvis readiness failed after " + attempts
                        + " seconds; process_alive=" + jarvisProcess.isAlive()
                        + ". Check the [OpenJarvis Swarm] logs above for errors.");
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

    public static void killJarvis() {
        killJarvisAndWait(4);
    }

    public static boolean killJarvisAndWait(int timeoutSeconds) {
        if (!isJarvisAlive()) {
            System.out.println("Ciel Debug: OpenJarvis is already stopped.");
            return true;
        }
        try {
            System.out.println("Ciel Debug: Sending graceful shutdown signal to OpenJarvis...");
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL("http://localhost:8000/shutdown").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            int status = connection.getResponseCode();
            System.out.println("Ciel Debug: OpenJarvis shutdown endpoint returned HTTP " + status + ".");
            connection.disconnect();
            long deadline = System.currentTimeMillis() + Math.max(1, timeoutSeconds) * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (!isJarvisAlive()) {
                    System.out.println("Ciel Debug: OpenJarvis shutdown confirmed; port 8000 is closed.");
                    return true;
                }
                Thread.sleep(250);
            }
            System.err.println("Ciel Warning: OpenJarvis did not stop within " + timeoutSeconds + " seconds.");
            return false;
        } catch (Exception e) {
            boolean stopped = !isJarvisAlive();
            System.out.println("Ciel " + (stopped ? "Debug" : "Warning") + ": OpenJarvis shutdown request ended with " + e.getMessage());
            return stopped;
        }
    }

    private static boolean reportUpdaterStatus() {
        Path statusFile = ProjectPaths.ciel().resolve("logs/update_status.txt");
        if (!Files.isRegularFile(statusFile)) return false;
        boolean updateBoot = false;
        try {
            System.out.println("[Ciel Update Status] Previous updater report:");
            List<String> lines = Files.readAllLines(statusFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                System.out.println("[Ciel Update Status] " + line);
                if (line.trim().equalsIgnoreCase("event=paired-update-installed")) updateBoot = true;
            }
            Path reportedFile = statusFile.resolveSibling("update_status.last_reported.txt");
            Files.move(statusFile, reportedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Ciel Warning: Could not read/acknowledge updater status: " + e.getMessage());
        }
        return updateBoot;
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
            long lastBindErrorLog = 0;
            while (!bindSuccess && !Thread.currentThread().isInterrupted()) {
                try (ServerSocket serverSocket = new ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
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
                    long now = System.currentTimeMillis();
                    if (now - lastBindErrorLog >= 60_000) {
                        System.err.println("Ciel Error: Could not bind trigger port " + port + " (" + e.getClass().getSimpleName()
                                + ": " + e.getMessage() + "). Retrying in 5 seconds.");
                        lastBindErrorLog = now;
                    }
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "Ciel-Trigger-Listener-" + port).start();
    }
}
