package com.cielcompanion.service;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.*;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

public class AzureSpeechService {

    private static SpeechConfig config;
    private static boolean isInitialized = false;
    
    private static final String CACHE_DIR_PATH = "voice_cache";
    private static final String DND_CACHE_DIR_PATH = "dnd_voice_cache";
    
    // HARDWARE LOCKS
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
    
    // EXPLICIT HARDWARE KILL SWITCH
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

    public static boolean speak(String text, String key, String style, String pitch) {
        return speak(text, key, style, pitch, "ja-JP");
    }

    public static boolean speak(String text, String key, String style, String pitch, String langCode) {
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
                return playWav(cachedFile);
            }

            long estimatedSeconds = (SpeechService.estimateSpeechDuration(text) / 1000) + 1;
            if (!AzureUsageTracker.canSpeak(estimatedSeconds)) {
                System.out.println("[Azure TTS] Quota exceeded. Cannot generate new static file: " + safeFilename);
                return false;
            }

            return generateAndPlayFile(text, safeStyle, safePitch, safeLang, cachedFile);
        } else {
            long estimatedSeconds = (SpeechService.estimateSpeechDuration(text) / 1000) + 1;
            if (!AzureUsageTracker.canSpeak(estimatedSeconds)) {
                System.out.println("[Azure TTS] Quota exceeded. Cannot stream dynamic speech.");
                return false;
            }
            return streamDirectly(text, safeStyle, safePitch, safeLang);
        }
    }

    private static boolean generateAndPlayFile(String text, String style, String pitch, String lang, File destination) {
        AudioConfig fileOutput = null;
        try {
            System.out.println("[Azure TTS] Generating new static file: " + destination.getName() + " [Style: " + style + ", Lang: " + lang + "]");
            
            fileOutput = AudioConfig.fromWavFileOutput(destination.getAbsolutePath());
            activeSynthesizer = new SpeechSynthesizer(config, fileOutput);

            String ssml = buildSsml(text, style, pitch, lang);
            SpeechSynthesisResult result = activeSynthesizer.SpeakSsml(ssml);

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                long bytes = result.getAudioData().length;
                long durationSeconds = bytes / 32000; 
                if (durationSeconds < 1) durationSeconds = 1;
                AzureUsageTracker.addUsage(durationSeconds);

                result.close();
                activeSynthesizer.close();
                fileOutput.close(); 
                activeSynthesizer = null;

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
            if (activeSynthesizer != null) {
                activeSynthesizer.close();
                activeSynthesizer = null;
            }
        }
        return false;
    }

    private static boolean streamDirectly(String text, String style, String pitch, String lang) {
        try {
            System.out.println("[Azure TTS] Streaming dynamic content (Style: " + style + ", Lang: " + lang + ")...");
            
            AudioConfig audioConfig = AudioConfig.fromDefaultSpeakerOutput();
            activeSynthesizer = new SpeechSynthesizer(config, audioConfig);

            String ssml = buildSsml(text, style, pitch, lang);
            SpeechSynthesisResult result = activeSynthesizer.SpeakSsml(ssml);

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                long bytes = result.getAudioData().length;
                long durationSeconds = bytes / 32000;
                if (durationSeconds < 1) durationSeconds = 1;
                AzureUsageTracker.addUsage(durationSeconds);
                
                result.close();
                return true;
            } else {
                logError(result);
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
            activeClip = clip; // Attach to hardware monitor
            
            clip.open(audioStream);
            clip.start();
            
            while (!clip.isRunning()) Thread.sleep(10);
            while (clip.isRunning()) Thread.sleep(10);
            
            clip.close();
            activeClip = null;
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // CRITICAL FIX: Kill the clip if Java thread is interrupted
            if (activeClip != null) {
                activeClip.stop();
                activeClip.close();
                activeClip = null;
            }
            System.out.println("Ciel Debug: Audio playback interrupted manually.");
            return true; 
        } catch (Exception e) {
            System.err.println("[Audio Player] Failed to play WAV: " + e.getMessage());
            return false;
        }
    }
}