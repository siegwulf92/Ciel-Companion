package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.ObserverService;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.ui.CielGui;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.vosk.Model;
import org.vosk.Recognizer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class VoiceListener {
    private static final String[] MIC_PRIORITY = {"Microphone (NVIDIA Broadcast)", "Focusrite", "Default Input"};
    private static final int PRIVILEGED_MODE_DURATION_SECONDS = 10;
    private static final double MIN_CONFIDENCE = 0.50;
    
    // ADDED "feel", "fill", "she'll" to catch more STT errors
    private static final String WAKE_WORD_REGEX = "^(?:hey\\s+|hi\\s+|uh\\s+|um\\s+|ok\\s+|okay\\s+|so\\s+|well\\s+)?(ciel|cl|seal|seo|ceo|joe|chill|tell|feel|fill|she'll|c l|see l|see el|see i|still|steel|steal|sail|sale|shell|hey allison|he see our|cl what|see how can you want|how can you open|he see our launch|hunter|so listen|ceo listen)";
    private static final Pattern WAKE_WORD_PATTERN = Pattern.compile(WAKE_WORD_REGEX, Pattern.CASE_INSENSITIVE);
    
    private static final Pattern SEARCH_TRIGGER_PATTERN = Pattern.compile("^(ciel search|cl search|seal search|seel search|seo search)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> PHANTOM_NOISES = Set.of("huh", "the", "a", "an", "who", "what");
    
    private final AtomicBoolean isMuted = new AtomicBoolean(false);
    private final AtomicBoolean isInternallyMuted = new AtomicBoolean(false); 

    private static final long DEAD_STREAM_THRESHOLD_MS = 5000;
    private final AtomicBoolean needsMicReinitialization = new AtomicBoolean(false);

    private Model voskModel;
    private TargetDataLine microphone;
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private Thread listeningThread;
    private final CommandService commandService;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private volatile boolean isRunning = true;
    private boolean isInitialized = false;

    public VoiceListener(CommandService commandService) {
        this.commandService = commandService;
    }

    public void initialize() {
        System.out.println("Ciel Debug: Initializing Voice Listener...");
        try {
            String modelPath = Paths.get(System.getProperty("user.dir"), "model").toString();
            voskModel = new Model(modelPath);
            isInitialized = true;
            ObserverService.initialize(); 
        } catch (IOException e) {
            System.err.println("Ciel FATAL Error: Could not load Vosk model.");
        }
    }

    public void initializeMicrophoneAsync() {
        if (!isInitialized) return;
        new Thread(() -> {
            try {
                microphone = getTargetDataLine();
                if (microphone != null) {
                    microphone.open(new AudioFormat(16000, 16, 1, true, false));
                    microphone.start();
                    startContinuousListening();
                    System.out.println("Ciel Debug: Microphone line opened. Continuous listening started.");
                } else {
                    System.err.println("Ciel Error: No compatible microphone could be found.");
                }
            } catch (LineUnavailableException e) {
                System.err.println("Ciel Error: Microphone line is unavailable.");
            }
        }, "Ciel-Mic-Initializer").start();
    }

    public void refresh() {
        System.out.println("Ciel Debug: Refreshing Voice Listener state...");
        needsMicReinitialization.set(true); 
        if (microphone != null) {
            microphone.close();
        }
        if (listeningThread == null || !listeningThread.isAlive()) {
            initializeMicrophoneAsync();
        }
    }

    private TargetDataLine getTargetDataLine() throws LineUnavailableException {
        for (int i = 0; i < 8; i++) { 
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().toLowerCase().contains(MIC_PRIORITY[0].toLowerCase())) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, new AudioFormat(16000, 16, 1, true, false));
                    if (mixer.isLineSupported(info)) return (TargetDataLine) mixer.getLine(info);
                }
            }
            if (i < 7) {
                try { Thread.sleep(30000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        for (int i = 1; i < MIC_PRIORITY.length; i++) {
            String micName = MIC_PRIORITY[i];
             for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().toLowerCase().contains(micName.toLowerCase())) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, new AudioFormat(16000, 16, 1, true, false));
                    if (mixer.isLineSupported(info)) return (TargetDataLine) mixer.getLine(info);
                }
            }
        }
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, new AudioFormat(16000, 16, 1, true, false));
        if (AudioSystem.isLineSupported(info)) return (TargetDataLine) AudioSystem.getLine(info);
        return null;
    }
    
    private void processRecognitionResult(String result) {
        if (!isProcessing.compareAndSet(false, true)) return; 
        try {
            boolean isPrivileged = ShortTermMemoryService.getMemory().isInPrivilegedMode();
            if (ShortTermMemoryService.getMemory().isInGamingSession() && !isPrivileged) return;

            JsonObject resultJson = JsonParser.parseString(result).getAsJsonObject();
            String transcribedText = resultJson.get("text").getAsString();

            if (transcribedText.isBlank() || PHANTOM_NOISES.contains(transcribedText)) return;

            boolean hasWakeWord = WAKE_WORD_PATTERN.matcher(transcribedText).find();

            if (ShortTermMemoryService.getMemory().isSearchModeActive()) {
                String[] words = transcribedText.split("\\s+");
                if (words.length < 3) return;
                System.out.printf("Ciel STT: Heard search query: \"%s\"%n", transcribedText);
                commandService.handleExplicitSearch(transcribedText);
                ShortTermMemoryService.getMemory().setSearchQueryEndTime(0);
                return;
            }

            double confidence = getConfidence(resultJson);
            if (confidence < MIN_CONFIDENCE) return;

            if (hasWakeWord) {
                String cleanedText = WAKE_WORD_PATTERN.matcher(transcribedText).replaceFirst("").trim();
                if (cleanedText.isEmpty()) {
                    return;
                }
            }
            
            commandService.handleCommand(transcribedText, hasWakeWord, () -> isProcessing.set(false));
        } finally {
            if (!commandService.isBusy()) isProcessing.set(false);
        }
    }

    public void toggleListening() {
        boolean currentMuteState;
        boolean newMuteState;
        do {
            currentMuteState = isMuted.get();
            newMuteState = !currentMuteState;
        } while (!isMuted.compareAndSet(currentMuteState, newMuteState));

        String lineKey = newMuteState ? "command.toggle_listening.off" : "command.toggle_listening.on";
        LineManager.getDialogueLine(lineKey).ifPresent(line -> SpeechService.speakPreformatted(line.text()));
        CielState.setManuallyMuted(newMuteState);
    }

    public void setInternalMute(boolean isMuted) { isInternallyMuted.set(isMuted); }
    public void forceMicReinitialization() { refresh(); }

    public void startListeningForCommand() {
        LineManager.getWakeWordAckLine().ifPresent(line -> {
            long speechDuration = SpeechService.estimateSpeechDuration(line.text());
            ShortTermMemoryService.getMemory().setPrivilegedMode(true, PRIVILEGED_MODE_DURATION_SECONDS + (int)(speechDuration/1000));
            SpeechService.speakPreformatted(line.text());
        });
    }

    public void startListeningForSearchQuery() {
        LineManager.getDialogueLine("command.search_ack.0").ifPresent(line -> {
            long speechDuration = SpeechService.estimateSpeechDuration(line.text());
            long listenStartTime = System.currentTimeMillis() + speechDuration;
            ShortTermMemoryService.getMemory().setSearchQueryEndTime(listenStartTime + 10000);
            SpeechService.speakPreformatted(line.text());
        });
    }
    
    private String buildGrammar() {
        Set<String> grammarSet = new HashSet<>();
        LineManager.getEasterEggKeys().stream()
            .map(s -> s.toLowerCase().replaceAll("[^a-zA-Z0-9\\s]", "").trim())
            .filter(s -> !s.isEmpty()).forEach(grammarSet::add);

        try (InputStream is = getClass().getResourceAsStream("/forced_glossary.json")) {
            if (is != null) {
                List<String> glossary = new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), new TypeToken<List<String>>(){}.getType());
                grammarSet.addAll(glossary.stream().map(s -> s.toLowerCase().replaceAll("[^a-zA-Z0-9\\s]", "").trim()).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
            }
        } catch (Exception e) {}
        return new Gson().toJson(grammarSet);
    }

    private void startContinuousListening() {
        listeningThread = new Thread(() -> {
            long lastAudioTime = System.currentTimeMillis();
            while (isRunning) {
                if (needsMicReinitialization.compareAndSet(true, false)) {
                    reinitializeMicrophone();
                    lastAudioTime = System.currentTimeMillis();
                }
                try (Recognizer recognizer = new Recognizer(voskModel, 16000, buildGrammar())) {
                    recognizer.setWords(true);
                    while (isRunning && microphone != null && microphone.isOpen() && !needsMicReinitialization.get()) {
                        if (isMuted.get() || isInternallyMuted.get()) { 
                            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                            lastAudioTime = System.currentTimeMillis();
                            continue;
                        }
                        byte[] buffer = new byte[4096];
                        int bytesRead = microphone.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            lastAudioTime = System.currentTimeMillis();
                            if (recognizer.acceptWaveForm(buffer, bytesRead)) processRecognitionResult(recognizer.getResult());
                        }
                        if (System.currentTimeMillis() - lastAudioTime > DEAD_STREAM_THRESHOLD_MS) {
                            needsMicReinitialization.set(true);
                            break;
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {}
                }
            }
        });
        listeningThread.setName("Ciel-ContinuousListener");
        listeningThread.setDaemon(true);
        listeningThread.start();
    }

    private synchronized void reinitializeMicrophone() {
        if (microphone != null) { try { microphone.stop(); microphone.close(); } catch (Exception e) {} }
        try {
            microphone = getTargetDataLine();
            if (microphone != null) {
                microphone.open(new AudioFormat(16000, 16, 1, true, false));
                microphone.start();
            }
        } catch (Exception e) {}
    }
    
    private double getConfidence(JsonObject resultJson) {
        if (resultJson.has("result")) {
            return resultJson.getAsJsonArray("result").asList().stream()
                .map(JsonElement::getAsJsonObject).map(obj -> obj.get("conf").getAsDouble())
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        return 0.0;
    }

    public void close() {
        isRunning = false;
        if (listeningThread != null) listeningThread.interrupt();
        commandExecutor.shutdownNow();
        if (microphone != null) microphone.close();
        if (voskModel != null) voskModel.close();
    }
}