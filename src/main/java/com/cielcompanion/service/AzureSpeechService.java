package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.ui.CielGui;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.*;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AzureSpeechService {

    private static SpeechConfig config;
    private static boolean isInitialized = false;
    
    public static boolean isSimulatingKeystroke = false;
    public static long lastSimulatedInputTime = 0;
    
    // --- CRITICAL FLAG FOR SAPI ROUTING ---
    public static volatile boolean isIntentionalCancellation = false;
    
    private static final String CACHE_DIR_PATH = "voice_cache";
    private static final String DND_CACHE_DIR_PATH = "dnd_voice_cache";
    // Wake output devices that suppress initial audio after a quiet period. The cue is
    // intentionally quiet and is followed by enough time for the endpoint to settle.
    private static final int SPEAKER_PREROLL_MILLIS = 2500;
    // About 24 dB quieter than the initial cue, while keeping non-zero PCM for wake detection.
    private static final double SPEAKER_WAKE_TONE_AMPLITUDE = 0.0011;
    private static final double SPEAKER_WAKE_TONE_HZ = 660.0;
    
    private static Clip activeClip = null;
    private static SpeechSynthesizer activeSynthesizer = null;
    private static final AtomicReference<Process> activeProcess = new AtomicReference<>();

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
    
    // --- DYNAMIC BYPASS FOR CACHING ---
    public static boolean isDynamicKey(String key) {
        return key == null || key.isBlank() || key.equals("game_launch") || key.equals("media_commentary") || key.startsWith("dynamic");
    }

    public static boolean isCached(String key, String style, String langCode) {
        if (isDynamicKey(key)) return false; // Never cache dynamic AI outputs
        
        String safeStyle = (style == null || style.isBlank() || style.equalsIgnoreCase("default")) ? "default" : style;
        String safeLang = (langCode == null || langCode.isBlank()) ? "ja-JP" : langCode;
        
        boolean isDndContent = safeLang.equalsIgnoreCase("en-US");
        String targetCacheDir = isDndContent ? DND_CACHE_DIR_PATH : CACHE_DIR_PATH;

        String suffix = safeLang.equalsIgnoreCase("ja-JP") ? "" : "_" + safeLang;
        String safeFilename = key.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + safeStyle + suffix + ".wav";
        File cachedFile = new File(targetCacheDir, safeFilename);

        return cachedFile.exists();
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
        Process p = activeProcess.get();
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
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

        if (!isDynamicKey(key)) {
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
            
            return generateAndPlayDynamicFile(text, safeStyle, safePitch, safeLang, key);
        }
    }

    /**
     * Dynamic speech uses the same WAV and Java Sound output path as cached speech.
     * This keeps device selection, output priming, and cancellation behavior consistent.
     */
    private static boolean generateAndPlayDynamicFile(String text, String style, String pitch, String lang, String key) {
        String targetCacheDir = lang.equalsIgnoreCase("en-US") ? DND_CACHE_DIR_PATH : CACHE_DIR_PATH;
        File cacheDir = new File(targetCacheDir);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            System.err.println("[Azure TTS] Could not create WAV playback directory: " + cacheDir.getAbsolutePath());
            return false;
        }

        File temporaryWav = null;
        try {
            temporaryWav = File.createTempFile("ciel_dynamic_", ".wav", cacheDir);
            System.out.println("[Azure TTS] Synthesizing dynamic speech to the shared WAV playback path (Key: "
                    + (key == null ? "Dynamic" : key) + ").");
            return generateAndPlayFile(text, style, pitch, lang, temporaryWav, key);
        } catch (Exception e) {
            System.err.println("[Azure TTS] Dynamic WAV synthesis/playback failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return false;
        } finally {
            if (temporaryWav != null && temporaryWav.exists() && !temporaryWav.delete()) {
                temporaryWav.deleteOnExit();
            }
        }
    }

    private static boolean generateAndPlayFile(String text, String style, String pitch, String lang, File destination, String key) {
        AudioConfig fileOutput = null;
        try {
            System.out.println("[Azure TTS] Generating WAV for playback: " + destination.getName());
            
            fileOutput = AudioConfig.fromWavFileOutput(destination.getAbsolutePath());
            activeSynthesizer = new SpeechSynthesizer(config, fileOutput);

            String ssml = buildSsml(text, style, pitch, lang, false);
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
            String ssml = buildSsml(text, style, pitch, lang, true);
            
            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));
            
            SpeechSynthesisResult result = activeSynthesizer.SpeakSsml(ssml);

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                long durationSeconds = Math.max(1, result.getAudioData().length / 32000);
                AzureUsageTracker.addUsage(durationSeconds);
                result.close();
                return true;
            } else if (result.getReason() == ResultReason.Canceled) {
                SpeechSynthesisCancellationDetails cancellation = SpeechSynthesisCancellationDetails.fromResult(result);
                
                // --- CLEAN SAPI FALLBACK (Rate = 0, Hardcoded Haruka) ---
                // A stop requested by the speech controller is authoritative.
                // Azure can report an intentional StopSpeakingAsync as an error;
                // treating that as a service failure launches SAPI for the old
                // line while the higher-priority return line is starting.
                if (!isIntentionalCancellation) {
                    System.out.println("Ciel Warning: Azure Stream canceled internally. Reason: " + cancellation.getErrorDetails());
                    System.out.println("Ciel Debug: Executing clean SAPI Fallback (Microsoft Haruka Desktop) for string: " + text);
                    
                    String safeSsml = buildFallbackSsml(text, lang).replace("'", "''");
                    String psScript = "$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                            + "Add-Type -AssemblyName System.Speech; "
                            + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                            + "$s.SetOutputToDefaultAudioDevice(); "
                            + "try { $s.SelectVoice('Microsoft Haruka Desktop'); } catch { try { $s.SelectVoiceByHints('Female') } catch {} } "
                            + "$s.Rate = 0; "
                            + "$s.SpeakSsml('" + safeSsml + "'); "
                            + "$s.Dispose();";
                    
                    String encodedCommand = Base64.getEncoder().encodeToString(psScript.getBytes(StandardCharsets.UTF_16LE));

                    ProcessBuilder pb = new ProcessBuilder("pwsh.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedCommand);
                    try {
                        Process p = pb.start();
                        activeProcess.set(p);
                        p.waitFor(15, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    } finally {
                        activeProcess.set(null);
                    }
                    
                    result.close();
                    return true; // We handled it with SAPI
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

    private static String buildSsml(String text, String style, String pitch, String lang, boolean warmSpeakerOutput) {
        if ("ja-JP".equalsIgnoreCase(lang)) {
            text = com.cielcompanion.util.SpeechText.normalizeKana(text);
            if (java.util.regex.Pattern.compile("[A-Za-z0-9]").matcher(text).find()) {
                text = com.cielcompanion.util.SpeechText.localKatakana(text);
            }
        }
        String currentVoiceName = CielVoiceManager.getActiveVoiceName();
        if (lang.equals("en-US") && currentVoiceName.contains("Nanami")) {
             currentVoiceName = "en-US-JennyNeural";
        }

        // CRITICAL FIX: Escape XML special characters so AI syntax doesn't break Azure's parser
        String safeText = text.replace("&", "&amp;")
                              .replace("<", "&lt;")
                              .replace(">", "&gt;")
                              .replace("\"", "&quot;")
                              .replace("'", "&apos;");

        StringBuilder ssml = new StringBuilder();
        ssml.append("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"").append(lang).append("\">");
        ssml.append("<voice name=\"").append(currentVoiceName).append("\">");
        
        boolean useStyle = !style.equals("default");
        if (useStyle) ssml.append("<mstts:express-as style=\"").append(style).append("\">");
        
        ssml.append("<prosody pitch=\"").append(pitch).append("\">");
        if (warmSpeakerOutput) ssml.append("<break time=\"").append(SPEAKER_PREROLL_MILLIS).append("ms\"/>");
        ssml.append(safeText).append("</prosody>");
        
        if (useStyle) ssml.append("</mstts:express-as>");
        ssml.append("</voice></speak>");
        return ssml.toString();
    }

    public static String buildFallbackSsml(String text, String lang) {
        String safeLang = (lang != null && lang.matches("[A-Za-z]{2}-[A-Za-z]{2,4}")) ? lang : "ja-JP";
        String safeText = text.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\""
                + safeLang + "\"><silence msec=\"" + SPEAKER_PREROLL_MILLIS + "\"/>" + safeText + "</speak>";
    }

    private static boolean playWav(File file, String key) {
        Clip clip = null;
        try (javax.sound.sampled.AudioInputStream audioStream = AudioSystem.getAudioInputStream(file)) {
            AudioFormat format = audioStream.getFormat();
            DataLine.Info info = new DataLine.Info(Clip.class, format);
            Mixer defaultOutput = AudioSystem.getMixer(null);
            clip = (Clip) defaultOutput.getLine(info);
            activeClip = clip; 
            long prerollFrames = 0;
            javax.sound.sampled.AudioInputStream playbackStream = audioStream;
            int frameSize = format.getFrameSize();
            float frameRate = format.getFrameRate();
            if (frameSize > 0 && frameRate > 0 && audioStream.getFrameLength() >= 0) {
                prerollFrames = Math.max(1L, Math.round(frameRate * SPEAKER_PREROLL_MILLIS / 1000.0));
                byte[] warmup = buildSpeakerWarmup(format, prerollFrames);
                playbackStream = new javax.sound.sampled.AudioInputStream(
                        new SequenceInputStream(new ByteArrayInputStream(warmup), audioStream),
                        format,
                        audioStream.getFrameLength() + prerollFrames);
            }
            clip.open(playbackStream);
            if (!clip.isOpen()) throw new IllegalStateException("Java Sound output line did not open");
            if (playbackStream != audioStream) playbackStream.close();
            
            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));
            
            clip.start();
            long readyTimeoutMillis = Math.max(5000L, SPEAKER_PREROLL_MILLIS + 3000L);
            long readyDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMillis);
            while (!clip.isRunning() && System.nanoTime() < readyDeadline) {
                Thread.sleep(10);
            }
            if (!clip.isRunning()) throw new IllegalStateException("Java Sound output line did not start");
            while (clip.getLongFramePosition() < prerollFrames && clip.isRunning()
                    && System.nanoTime() < readyDeadline) {
                Thread.sleep(10);
            }
            if (clip.getLongFramePosition() < prerollFrames)
                throw new IllegalStateException("Speaker pre-roll did not complete before timeout");
            System.out.println("[Azure TTS] Default output primed before WAV speech playback (key="
                    + (key == null ? "Dynamic" : key) + ", mixer=" + defaultOutput.getMixerInfo().getName()
                    + ", preroll_ms=" + SPEAKER_PREROLL_MILLIS + ", wake_cue=very_quiet_660hz_pcm).");
            long playbackDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    Math.max(5000L, clip.getMicrosecondLength() / 1000L + 5000L));
            while (clip.isRunning() && System.nanoTime() < playbackDeadline) Thread.sleep(50);
            if (clip.isRunning()) {
                System.err.println("[Azure TTS] Cached audio exceeded its playback deadline; stopping the output line.");
                clip.stop();
            }
            return true;
        } catch (InterruptedException e) {
            System.out.println("Ciel Debug: Audio playback was intentionally interrupted/cancelled.");
            Thread.currentThread().interrupt(); 
            return true; 
        } catch (Exception e) {
            System.err.println("[Azure TTS] Cached audio playback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            if (clip != null) {
                try { clip.stop(); } catch (Exception ignored) {}
                try { clip.close(); } catch (Exception ignored) {}
                if (activeClip == clip) activeClip = null;
            }
        }
    }

    /**
     * Put two low-level tone bursts in the pre-roll. A silent pre-roll does not wake
     * some powered speakers, so these sacrificial samples exercise the endpoint before
     * speech begins. The second burst is near the end of the settling window so the
     * endpoint has recent non-silent output immediately before the first phoneme.
     */
    private static byte[] buildSpeakerWarmup(AudioFormat format, long frameCount) {
        int frameSize = format.getFrameSize();
        byte[] warmup = new byte[Math.toIntExact(frameCount * frameSize)];
        if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                || format.getSampleSizeInBits() != 16 || frameSize <= 0) {
            System.out.println("[Azure TTS] PCM wake cue unavailable for output format " + format
                    + "; using silent pre-roll.");
            return warmup;
        }

        int channels = Math.max(1, format.getChannels());
        int sampleRate = Math.round(format.getSampleRate());
        int burstFrames = Math.max(1, Math.round(sampleRate * 0.16f));
        int firstStart = Math.max(0, Math.round(sampleRate * 0.10f));
        int secondStart = Math.max(firstStart + burstFrames, Math.round(sampleRate * 1.90f));
        boolean bigEndian = format.isBigEndian();

        writeWakeBurst(warmup, frameSize, channels, firstStart, burstFrames, sampleRate, bigEndian);
        writeWakeBurst(warmup, frameSize, channels, secondStart, burstFrames, sampleRate, bigEndian);
        return warmup;
    }

    private static void writeWakeBurst(byte[] target, int frameSize, int channels, int startFrame,
                                       int burstFrames, int sampleRate, boolean bigEndian) {
        for (int i = 0; i < burstFrames; i++) {
            long frame = (long) startFrame + i;
            long byteOffset = frame * frameSize;
            if (byteOffset + frameSize > target.length) break;

            double edge = Math.min(1.0, Math.min(i / Math.max(1.0, sampleRate * 0.015),
                    (burstFrames - 1 - i) / Math.max(1.0, sampleRate * 0.015)));
            double sample = Math.sin(2.0 * Math.PI * SPEAKER_WAKE_TONE_HZ * i / sampleRate)
                    * SPEAKER_WAKE_TONE_AMPLITUDE * Math.max(0.0, edge);
            short pcm = (short) Math.round(sample * Short.MAX_VALUE);
            for (int channel = 0; channel < channels; channel++) {
                int index = Math.toIntExact(byteOffset + (long) channel * 2);
                if (index + 1 >= target.length) break;
                target[index] = (byte) (bigEndian ? (pcm >>> 8) : pcm);
                target[index + 1] = (byte) (bigEndian ? pcm : (pcm >>> 8));
            }
        }
    }
}
