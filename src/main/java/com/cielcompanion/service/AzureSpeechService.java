package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.ui.CielGui;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.*;

import javax.sound.sampled.*;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AzureSpeechService {

    private static SpeechConfig config;
    private static boolean isInitialized = false;
    
    public static boolean isSimulatingKeystroke = false;
    public static long lastSimulatedInputTime = 0;
    
    // --- CRITICAL FLAG FOR SAPI ROUTING ---
    public static volatile boolean isIntentionalCancellation = false;
    
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
        isIntentionalCancellation = true;
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

    public static synchronized boolean speak(String text, String key, String style, String pitch) {
        return speak(text, key, style, pitch, "ja-JP");
    }

    public static synchronized boolean speak(String text, String key, String style, String pitch, String langCode) {
        if (!isInitialized) return false;
        
        isIntentionalCancellation = false;

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

            return generateAndPlayFile(text, safeStyle, safePitch, safeLang, cachedFile, key);
            
        } else {
            long estimatedSeconds = (SpeechService.estimateSpeechDuration(text) / 1000) + 1;
            if (!AzureUsageTracker.canSpeak(estimatedSeconds)) {
                return false;
            }
            
            return streamDirectly(text, safeStyle, safePitch, safeLang, key);
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
            } else if (result.getReason() == ResultReason.Canceled) {
                SpeechSynthesisCancellationDetails cancellation = SpeechSynthesisCancellationDetails.fromResult(result);
                if (cancellation.getReason() == CancellationReason.Error) {
                    System.out.println("Ciel Error: Azure File Error: " + cancellation.getErrorDetails());
                }
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
        try {
            System.out.println("[Azure TTS] Streaming dynamic content: \"" + text + "\"");
            
            AudioConfig audioConfig = AudioConfig.fromDefaultSpeakerOutput();
            activeSynthesizer = new SpeechSynthesizer(config, audioConfig);
            String ssml = buildSsml(text, style, pitch, lang);
            
            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));
            
            SpeechSynthesisResult result = activeSynthesizer.SpeakSsml(ssml);

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                long durationSeconds = Math.max(1, result.getAudioData().length / 32000);
                AzureUsageTracker.addUsage(durationSeconds);
                result.close();
                return true;
            } else if (result.getReason() == ResultReason.Canceled) {
                SpeechSynthesisCancellationDetails cancellation = SpeechSynthesisCancellationDetails.fromResult(result);
                
                // --- CRITICAL FIX: Gracefully bounce to SAPI if Azure rejects text for language mismatch! ---
                if (!isIntentionalCancellation || cancellation.getReason() == CancellationReason.Error) {
                    System.out.println("Ciel Warning: Azure Stream canceled internally (Likely language syntax rejection). Falling back to SAPI.");
                    result.close();
                    return false; 
                }
                System.out.println("Ciel Debug: Azure Speech stream intentionally canceled.");
                result.close();
                return true; 
            }
            result.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (activeSynthesizer != null) {
                activeSynthesizer.close();
                activeSynthesizer = null;
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
        try (javax.sound.sampled.AudioInputStream audioStream = AudioSystem.getAudioInputStream(file)) {
            DataLine.Info info = new DataLine.Info(Clip.class, audioStream.getFormat());
            Clip clip = (Clip) AudioSystem.getLine(info);
            activeClip = clip; 
            clip.open(audioStream);
            
            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));
            
            clip.start();
            long durationMs = clip.getMicrosecondLength() / 1000;
            Thread.sleep(durationMs + 200); 
            
            clip.close();
            activeClip = null;
            return true;
        } catch (InterruptedException e) {
            System.out.println("Ciel Debug: Audio playback was intentionally interrupted/cancelled.");
            Thread.currentThread().interrupt(); 
            return true; 
        } catch (Exception e) {
            return false;
        }
    }
}