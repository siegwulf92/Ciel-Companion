package com.cielcompanion.service;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.*;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

public class AzureSpeechService {

    private static SpeechConfig config;
    private static String voiceName;
    private static boolean isInitialized = false;
    
    // Updated cache directory to "voice_cache"
    private static final String CACHE_DIR_PATH = "voice_cache";

    // Initialize statically based on Settings
    public static void initialize() {
        String key = Settings.getAzureSpeechKey();
        String region = Settings.getAzureSpeechRegion();
        voiceName = Settings.getAzureVoiceName();

        if (key != null && !key.isBlank() && region != null && !region.isBlank()) {
            try {
                config = SpeechConfig.fromSubscription(key, region);
                config.setSpeechSynthesisVoiceName(voiceName);
                config.setSpeechSynthesisLanguage("ja-JP"); 
                
                // Ensure cache directory exists in the root folder
                new File(CACHE_DIR_PATH).mkdirs();
                
                isInitialized = true;
                System.out.println("[Azure Init] Service initialized with voice: " + voiceName);
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

    public static boolean speak(String text, String key, String style, String pitch) {
        if (!isInitialized) return false;

        String safeStyle = (style == null || style.isBlank() || style.equalsIgnoreCase("default")) ? "default" : style;
        String safePitch = (pitch == null || pitch.isBlank()) ? "+0%" : pitch;

        // --- SCENARIO A: Static Line (Key Provided) ---
        if (key != null && !key.isBlank()) {
            String safeFilename = key.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + safeStyle + ".wav";
            File cachedFile = new File(CACHE_DIR_PATH, safeFilename);

            // 1. Check Local Cache
            if (cachedFile.exists()) {
                System.out.println("[Azure TTS] Cache hit (Static): " + safeFilename);
                return playWav(cachedFile);
            }

            // 2. Not in cache: Check Quota
            long estimatedSeconds = (SpeechService.estimateSpeechDuration(text) / 1000) + 1;
            if (!AzureUsageTracker.canSpeak(estimatedSeconds)) {
                System.out.println("[Azure TTS] Quota exceeded. Cannot generate new static file: " + safeFilename);
                return false;
            }

            // 3. Generate and Save using SSML
            return generateAndPlayFile(text, safeStyle, safePitch, cachedFile);
        }

        // --- SCENARIO B: Dynamic Line (No Key) ---
        else {
            long estimatedSeconds = (SpeechService.estimateSpeechDuration(text) / 1000) + 1;
            if (!AzureUsageTracker.canSpeak(estimatedSeconds)) {
                System.out.println("[Azure TTS] Quota exceeded. Cannot stream dynamic speech.");
                return false;
            }

            // 2. Stream Directly using SSML
            return streamDirectly(text, safeStyle, safePitch);
        }
    }

    private static boolean generateAndPlayFile(String text, String style, String pitch, File destination) {
        SpeechSynthesizer synthesizer = null;
        AudioConfig fileOutput = null;
        try {
            System.out.println("[Azure TTS] Generating new static file: " + destination.getName() + " [Style: " + style + "]");
            
            fileOutput = AudioConfig.fromWavFileOutput(destination.getAbsolutePath());
            synthesizer = new SpeechSynthesizer(config, fileOutput);

            String ssml = buildSsml(text, style, pitch);
            SpeechSynthesisResult result = synthesizer.SpeakSsml(ssml);

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                long bytes = result.getAudioData().length;
                long durationSeconds = bytes / 32000; 
                if (durationSeconds < 1) durationSeconds = 1;
                AzureUsageTracker.addUsage(durationSeconds);

                result.close();
                synthesizer.close();
                fileOutput.close(); 
                synthesizer = null;

                return playWav(destination);
            } else {
                logError(result);
                if (destination.exists()) destination.delete();
            }
            result.close();
        } catch (Exception e) {
            e.printStackTrace();
            if (destination.exists()) destination.delete();
        } finally {
            if (synthesizer != null) synthesizer.close();
        }
        return false;
    }

    private static boolean streamDirectly(String text, String style, String pitch) {
        SpeechSynthesizer synthesizer = null;
        try {
            System.out.println("[Azure TTS] Streaming dynamic content (Style: " + style + ")...");
            
            AudioConfig audioConfig = AudioConfig.fromDefaultSpeakerOutput();
            synthesizer = new SpeechSynthesizer(config, audioConfig);

            String ssml = buildSsml(text, style, pitch);
            SpeechSynthesisResult result = synthesizer.SpeakSsml(ssml);

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                long bytes = result.getAudioData().length;
                long durationSeconds = bytes / 32000;
                if (durationSeconds < 1) durationSeconds = 1;
                AzureUsageTracker.addUsage(durationSeconds);
                
                result.close();
                synthesizer.close();
                return true;
            } else {
                logError(result);
            }
            result.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (synthesizer != null) synthesizer.close();
        }
        return false;
    }

    private static String buildSsml(String text, String style, String pitch) {
        StringBuilder ssml = new StringBuilder();
        ssml.append("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"ja-JP\">");
        ssml.append("<voice name=\"").append(voiceName).append("\">");
        
        boolean useStyle = !style.equals("default");
        
        if (useStyle) {
            ssml.append("<mstts:express-as style=\"").append(style).append("\">");
        }
        
        ssml.append("<prosody pitch=\"").append(pitch).append("\">");
        ssml.append(text);
        ssml.append("</prosody>");
        
        if (useStyle) {
            ssml.append("</mstts:express-as>");
        }
        
        ssml.append("</voice></speak>");
        return ssml.toString();
    }

    private static void logError(SpeechSynthesisResult result) {
        if (result.getReason() == ResultReason.Canceled) {
            SpeechSynthesisCancellationDetails cancellation = SpeechSynthesisCancellationDetails.fromResult(result);
            System.err.println("[Azure TTS] CANCELED: " + cancellation.getReason() + " | " + cancellation.getErrorDetails());
        }
    }

    private static boolean playWav(File file) {
        try (javax.sound.sampled.AudioInputStream audioStream = AudioSystem.getAudioInputStream(file)) {
            DataLine.Info info = new DataLine.Info(Clip.class, audioStream.getFormat());
            Clip clip = (Clip) AudioSystem.getLine(info);
            
            clip.open(audioStream);
            clip.start();
            
            while (!clip.isRunning()) Thread.sleep(10);
            while (clip.isRunning()) Thread.sleep(10);
            
            clip.close();
            return true;
        } catch (InterruptedException e) {
            // FIXED: Don't treat interruption as error. It means "User wants to stop."
            // We return true so SpeechService doesn't trigger SAPI backup.
            // Re-interrupt the thread to preserve the signal.
            Thread.currentThread().interrupt();
            System.out.println("Ciel Debug: Audio playback interrupted manually.");
            return true; 
        } catch (Exception e) {
            System.err.println("[Audio Player] Failed to play WAV: " + e.getMessage());
            return false;
        }
    }
}