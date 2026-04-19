package com.cielcompanion.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.*;

import javax.sound.sampled.*;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AzureSpeechService {

    private static SpeechConfig config;
    private static boolean isInitialized = false;
    
    // EXPOSED FLAG: Tracks timestamps of simulated keystrokes so SystemMonitor can ignore them for True Idle tracking
    public static boolean isSimulatingKeystroke = false;
    public static long lastSimulatedInputTime = 0;
    
    private static final String CACHE_DIR_PATH = "voice_cache";
    private static final String DND_CACHE_DIR_PATH = "dnd_voice_cache";
    
    private static Clip activeClip = null;
    private static SpeechSynthesizer activeSynthesizer = null;

    public static void initialize() {
        String key = Settings.getAzureSpeechKey();
        String region = Settings.getAzureSpeechRegion();

        if (key != null && !key.isBlank() && region != null && !region.isBlank()) {
            try {
                config = SpeechConfig.fromSubscription(key, region);
                config.setSpeechSynthesisLanguage("ja-JP"); 
                
                new File(CACHE_DIR_PATH).mkdirs();
                new File(DND_CACHE_DIR_PATH).mkdirs();
                
                isInitialized = true;
                System.out.println("[Azure Init] Service initialized.");
            } catch (Exception e) {
                System.err.println("[Azure Init] Failed to initialize: " + e.getMessage());
                isInitialized = false;
            }
        } else {
            System.out.println("[Azure Init] Skipped. Key or Region missing.");
            isInitialized = false;
        }
    }

    public static boolean isAvailable() {
        return isInitialized;
    }
    
    public static void stopAllAudio() {
        if (activeClip != null && activeClip.isRunning()) {
            activeClip.stop();
            activeClip.close();
            activeClip = null;
        }
        if (activeSynthesizer != null) {
            try {
                activeSynthesizer.StopSpeakingAsync().get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            activeSynthesizer = null;
        }
    }

    private static String applyKatakanaTransliteration(String originalText, String langCode) {
        if (!"ja-JP".equalsIgnoreCase(langCode)) return originalText;
        if (!originalText.matches(".*[a-zA-Z].*")) return originalText; 

        try {
            System.out.println("[Azure TTS] English text detected. Requesting Katakana transliteration from Swarm...");
            URL url = new URL("http://localhost:8000/transliterate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000); 
            conn.setReadTimeout(180000);   

            JsonObject jsonInput = new JsonObject();
            jsonInput.addProperty("text", originalText);
            
            try(OutputStream os = conn.getOutputStream()) {
                byte[] input = new Gson().toJson(jsonInput).getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            if (conn.getResponseCode() == 200) {
                JsonObject response = new Gson().fromJson(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8), JsonObject.class);
                if (response != null && response.has("katakana")) {
                    String katakana = response.get("katakana").getAsString();
                    System.out.println("[Azure TTS] Transliteration success: " + katakana);
                    return katakana;
                }
            }
        } catch (Exception e) {
            System.err.println("[Azure TTS] Transliteration network error: " + e.getMessage());
        }
        
        return originalText; 
    }

    public static synchronized boolean speak(String text, String key, String style, String pitch) {
        return speak(text, key, style, pitch, "ja-JP");
    }

    public static synchronized boolean speak(String text, String key, String style, String pitch, String langCode) {
        if (!isInitialized) return false;

        String safeStyle = (style == null || style.isBlank() || style.equalsIgnoreCase("default")) ? "default" : style;
        String safePitch = (pitch == null || pitch.isBlank()) ? "+0%" : pitch;
        String safeLang = (langCode == null || langCode.isBlank()) ? "ja-JP" : langCode;
        
        boolean isDndContent = safeLang.equalsIgnoreCase("en-US");
        String targetCacheDir = isDndContent ? DND_CACHE_DIR_PATH : CACHE_DIR_PATH;

        if (key != null && !key.isBlank()) {
            String suffix = safeLang.equalsIgnoreCase("ja-JP") ? "" : "_" + safeLang;
            String safeFilename = key.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + safeStyle + suffix + ".wav";
            File cachedFile = new File(targetCacheDir, safeFilename);

            if (cachedFile.exists()) {
                System.out.println("[Azure TTS] Cache hit (Static): " + safeFilename);
                return playWav(cachedFile, key);
            }

            long estimatedSeconds = (SpeechService.estimateSpeechDuration(text) / 1000) + 1;
            if (!AzureUsageTracker.canSpeak(estimatedSeconds)) {
                System.out.println("[Azure TTS] Quota exceeded. Cannot generate new static file.");
                return false;
            }

            String processedText = applyKatakanaTransliteration(text, safeLang);
            return generateAndPlayFile(processedText, safeStyle, safePitch, safeLang, cachedFile, key);
            
        } else {
            long estimatedSeconds = (SpeechService.estimateSpeechDuration(text) / 1000) + 1;
            if (!AzureUsageTracker.canSpeak(estimatedSeconds)) {
                return false;
            }
            
            String processedText = applyKatakanaTransliteration(text, safeLang);
            return streamDirectly(processedText, safeStyle, safePitch, safeLang, key);
        }
    }

    private static boolean generateAndPlayFile(String text, String style, String pitch, String lang, File destination, String key) {
        AudioConfig fileOutput = null;
        try {
            System.out.println("[Azure TTS] Generating new static file: " + destination.getName());
            
            fileOutput = AudioConfig.fromWavFileOutput(destination.getAbsolutePath());
            activeSynthesizer = new SpeechSynthesizer(config, fileOutput);

            String ssml = buildSsml(text, style, pitch, lang);
            SpeechSynthesisResult result = activeSynthesizer.SpeakSsml(ssml);

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                long durationSeconds = Math.max(1, result.getAudioData().length / 32000);
                AzureUsageTracker.addUsage(durationSeconds);
                result.close();
                activeSynthesizer.close();
                fileOutput.close(); 
                activeSynthesizer = null;

                return playWav(destination, key);
            } else {
                if (destination.exists()) destination.delete();
            }
            result.close();
        } catch (Exception e) {
            if (destination.exists()) destination.delete();
        } finally {
            if (activeSynthesizer != null) {
                activeSynthesizer.close();
                activeSynthesizer = null;
            }
        }
        return false;
    }

    private static boolean streamDirectly(String text, String style, String pitch, String lang, String key) {
        boolean mediaPaused = false;
        boolean gamePaused = false;
        try {
            System.out.println("[Azure TTS] Streaming dynamic content: \"" + text + "\"");
            
            AudioConfig audioConfig = AudioConfig.fromDefaultSpeakerOutput();
            activeSynthesizer = new SpeechSynthesizer(config, audioConfig);
            String ssml = buildSsml(text, style, pitch, lang);
            
            String currentCat = HabitTrackerService.getCurrentCategory();
            boolean isAmbientLine = key != null && (key.toLowerCase().contains("phase") || key.toLowerCase().contains("return") || key.toLowerCase().contains("boot") || key.toLowerCase().contains("login"));
            boolean isMasterPresent = com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().getCurrentPhase() == 0;
            
            if ("Media".equals(currentCat) && !isAmbientLine && isMasterPresent) {
                mediaPaused = true;
                System.out.println("Ciel Debug: Auto-pausing media (Spacebar) for synchronized speech.");
                Robot robot = new Robot();
                isSimulatingKeystroke = true;
                lastSimulatedInputTime = System.currentTimeMillis();
                robot.keyPress(KeyEvent.VK_SPACE);
                robot.keyRelease(KeyEvent.VK_SPACE);
                Thread.sleep(600); 
                isSimulatingKeystroke = false;
            } else if ("Gaming".equals(currentCat) && !isAmbientLine && isMasterPresent) {
                if (HabitTrackerService.isCurrentGamePausable()) {
                    gamePaused = true;
                    System.out.println("Ciel Debug: Auto-pausing single-player game (ESC) for critical synchronized speech.");
                    Robot robot = new Robot();
                    isSimulatingKeystroke = true;
                    lastSimulatedInputTime = System.currentTimeMillis();
                    robot.keyPress(KeyEvent.VK_ESCAPE);
                    robot.keyRelease(KeyEvent.VK_ESCAPE);
                    Thread.sleep(600); 
                    isSimulatingKeystroke = false;
                }
            }

            SpeechSynthesisResult result = activeSynthesizer.SpeakSsml(ssml);

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                long durationSeconds = Math.max(1, result.getAudioData().length / 32000);
                AzureUsageTracker.addUsage(durationSeconds);
                result.close();
                return true;
            } else if (result.getReason() == ResultReason.Canceled) {
                System.out.println("Ciel Debug: Azure Speech stream intentionally canceled.");
                result.close();
                return true; // Prevents the SAPI fallback from triggering!
            }
            result.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (activeSynthesizer != null) {
                activeSynthesizer.close();
                activeSynthesizer = null;
            }
            if (mediaPaused) {
                try {
                    Thread.sleep(600);
                    System.out.println("Ciel Debug: Auto-unpausing media (Spacebar).");
                    Robot robot = new Robot();
                    isSimulatingKeystroke = true;
                    lastSimulatedInputTime = System.currentTimeMillis();
                    robot.keyPress(KeyEvent.VK_SPACE);
                    robot.keyRelease(KeyEvent.VK_SPACE);
                    Thread.sleep(200);
                    isSimulatingKeystroke = false;
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static String buildSsml(String text, String style, String pitch, String lang) {
        String currentVoiceName = CielVoiceManager.getActiveVoiceName();
        if (lang.equals("en-US") && currentVoiceName.contains("Nanami")) {
             currentVoiceName = "en-US-JennyNeural";
        }

        StringBuilder ssml = new StringBuilder();
        ssml.append("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"").append(lang).append("\">");
        ssml.append("<voice name=\"").append(currentVoiceName).append("\">");
        
        boolean useStyle = !style.equals("default");
        if (useStyle) ssml.append("<mstts:express-as style=\"").append(style).append("\">");
        
        ssml.append("<prosody pitch=\"").append(pitch).append("\">").append(text).append("</prosody>");
        
        if (useStyle) ssml.append("</mstts:express-as>");
        ssml.append("</voice></speak>");
        return ssml.toString();
    }

    private static boolean playWav(File file, String key) {
        boolean mediaPaused = false;
        try (javax.sound.sampled.AudioInputStream audioStream = AudioSystem.getAudioInputStream(file)) {
            DataLine.Info info = new DataLine.Info(Clip.class, audioStream.getFormat());
            Clip clip = (Clip) AudioSystem.getLine(info);
            activeClip = clip; 
            clip.open(audioStream);
            
            String currentCat = HabitTrackerService.getCurrentCategory();
            boolean isAmbientLine = key != null && (key.toLowerCase().contains("phase") || key.toLowerCase().contains("return") || key.toLowerCase().contains("boot") || key.toLowerCase().contains("login"));
            boolean isMasterPresent = com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().getCurrentPhase() == 0;
            
            if ("Media".equals(currentCat) && !isAmbientLine && isMasterPresent) {
                mediaPaused = true;
                System.out.println("Ciel Debug: Auto-pausing media (Spacebar) for synchronized speech.");
                Robot robot = new Robot();
                isSimulatingKeystroke = true;
                lastSimulatedInputTime = System.currentTimeMillis();
                robot.keyPress(KeyEvent.VK_SPACE);
                robot.keyRelease(KeyEvent.VK_SPACE);
                Thread.sleep(600);
                isSimulatingKeystroke = false;
            } else if ("Gaming".equals(currentCat) && !isAmbientLine && isMasterPresent) {
                if (HabitTrackerService.isCurrentGamePausable()) {
                    System.out.println("Ciel Debug: Auto-pausing single-player game (ESC) for critical synchronized speech.");
                    Robot robot = new Robot();
                    isSimulatingKeystroke = true;
                    lastSimulatedInputTime = System.currentTimeMillis();
                    robot.keyPress(KeyEvent.VK_ESCAPE);
                    robot.keyRelease(KeyEvent.VK_ESCAPE);
                    Thread.sleep(600);
                    isSimulatingKeystroke = false;
                }
            }
            
            clip.start();
            long durationMs = clip.getMicrosecondLength() / 1000;
            Thread.sleep(durationMs + 200); 
            
            clip.close();
            activeClip = null;
            return true;
        } catch (InterruptedException e) {
            // CRITICAL SAPI GLITCH FIX: If SpeechService intentionally halts her speech upon your return, 
            // gracefully acknowledge the interrupt and return TRUE so the system doesn't hallucinate an error and fallback to SAPI.
            System.out.println("Ciel Debug: Audio playback was intentionally interrupted/cancelled.");
            Thread.currentThread().interrupt(); 
            return true; 
        } catch (Exception e) {
            return false;
        } finally {
            if (mediaPaused) {
                try {
                    Thread.sleep(600);
                    System.out.println("Ciel Debug: Auto-unpausing media (Spacebar).");
                    Robot robot = new Robot();
                    isSimulatingKeystroke = true;
                    lastSimulatedInputTime = System.currentTimeMillis();
                    robot.keyPress(KeyEvent.VK_SPACE);
                    robot.keyRelease(KeyEvent.VK_SPACE);
                    Thread.sleep(200);
                    isSimulatingKeystroke = false;
                } catch (Exception ignored) {}
            }
        }
    }
}