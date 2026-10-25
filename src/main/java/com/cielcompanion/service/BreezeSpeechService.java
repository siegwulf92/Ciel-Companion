package com.cielcompanion.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Hosted BreezeBlue TTS adapter.  Azure remains the normal fallback. */
public final class BreezeSpeechService {
    private static final String API_ROOT = "https://api.breeze.blue";
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile String apiKey;
    private static volatile String voiceId;
    private static final Path CACHE_DIR = Path.of(".local", "breeze_voice_cache");

    private BreezeSpeechService() {}

    public static void initialize() {
        if (initialized) return;
        synchronized (INIT_LOCK) {
            if (initialized) return;
            initialized = true;
            if (!Settings.isBreezeEnabled()) {
                System.out.println("[Breeze TTS] Disabled by configuration; Azure remains primary fallback.");
                return;
            }
            apiKey = Settings.getBreezeApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                System.out.println("[Breeze TTS] No BREEZE_API_KEY configured; Azure remains primary.");
                return;
            }
            voiceId = Settings.getBreezeVoiceId();
            if (voiceId == null || voiceId.isBlank()) voiceId = discoverVoice();
            if (voiceId == null || voiceId.isBlank()) {
                System.err.println("[Breeze TTS] No suitable voice found; Azure fallback will be used.");
                return;
            }
            available = true;
            System.out.println("[Breeze TTS] Ready (voice discovery/configuration complete; key withheld).");
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static boolean speak(String text, String key, String style, String pitch, String languageCode) {
        if (!available || text == null || text.isBlank()) return false;
        Path cached = cachePath(text, style, pitch, languageCode);
        if (Settings.isBreezeCacheEnabled() && Files.isRegularFile(cached)) {
            boolean played = AzureSpeechService.playWavFile(cached.toFile(), key == null ? "BreezeCached" : key);
            if (played) System.out.println("[Breeze TTS] Cache hit; no credits consumed.");
            return played;
        }
        if (!BreezeUsageTracker.reserve(text)) return false;
        HttpURLConnection connection = null;
        Path temp = null;
        try {
            URI uri = URI.create(API_ROOT + "/v1/text-to-speech/" +
                    URLEncoder.encode(voiceId, StandardCharsets.UTF_8).replace("+", "%20") + "?output_format=wav");
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(90_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("xi-api-key", apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JsonObject body = new JsonObject();
            body.addProperty("text", text);
            body.addProperty("model_id", Settings.getBreezeModel());
            body.addProperty("language_code", Settings.getBreezeLanguageCode());
            body.addProperty("instructions", instructions(style, pitch));
            JsonObject settings = new JsonObject();
            // Breeze's hosted API uses guidance_scale for instruction
            // adherence; an unsupported speed field is silently ignored by
            // some versions and rejected by others.
            settings.addProperty("guidance_scale", 4.0);
            body.add("voice_settings", settings);
            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(request); }

            int status = connection.getResponseCode();
            InputStream response = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] audio = response == null ? new byte[0] : readAll(response);
            if (status < 200 || status >= 300 || audio.length < 64) {
                System.err.println("[Breeze TTS] Generation failed (HTTP " + status + "); Azure fallback scheduled.");
                return false;
            }
            if (Settings.isBreezeCacheEnabled()) {
                Files.createDirectories(CACHE_DIR);
                temp = CACHE_DIR.resolve("." + cached.getFileName() + "." + UUID.randomUUID() + ".tmp");
            } else {
                Files.createDirectories(Path.of(".local"));
                temp = Path.of(".local", "breeze-" + UUID.randomUUID() + ".wav");
            }
            Files.write(temp, audio);
            if (Settings.isBreezeCacheEnabled()) {
                Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                temp = null;
            }
            Path playable = Settings.isBreezeCacheEnabled() ? cached : temp;
            boolean played = AzureSpeechService.playWavFile(playable.toFile(), key == null ? "BreezeDynamic" : key);
            if (!played) System.err.println("[Breeze TTS] Audio generated but playback failed; Azure fallback scheduled.");
            return played;
        } catch (Exception e) {
            System.err.println("[Breeze TTS] Request failed (" + e.getClass().getSimpleName() + "); Azure fallback scheduled.");
            return false;
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) {}
            }
            if (connection != null) connection.disconnect();
        }
    }

    private static Path cachePath(String text, String style, String pitch, String languageCode) {
        String material = voiceId + "\n" + Settings.getBreezeModel() + "\n"
                + Settings.getBreezeLanguageCode() + "\n" + languageCode + "\n"
                + style + "\n" + pitch + "\n" + text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return CACHE_DIR.resolve(hex + ".wav");
        } catch (Exception e) {
            return CACHE_DIR.resolve(Integer.toHexString(material.hashCode()) + ".wav");
        }
    }

    private static String discoverVoice() {
        HttpURLConnection connection = null;
        try {
            String query = "search=" + URLEncoder.encode("female Japanese roleplay companion", StandardCharsets.UTF_8)
                    + "&language_code=en&gender=female&accent=japanese&page_size=50";
            connection = (HttpURLConnection) URI.create(API_ROOT + "/v1/voices?" + query).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("xi-api-key", apiKey);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300 || connection.getInputStream() == null) {
                System.err.println("[Breeze TTS] Voice discovery failed (HTTP " + status + ").");
                return "";
            }
            JsonObject payload = JsonParser.parseString(new String(readAll(connection.getInputStream()), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray voices = payload.has("voices") && payload.get("voices").isJsonArray()
                    ? payload.getAsJsonArray("voices") : new JsonArray();
            List<JsonObject> candidates = new ArrayList<>();
            for (JsonElement element : voices) if (element.isJsonObject()) candidates.add(element.getAsJsonObject());
            return candidates.stream().max(Comparator.comparingInt(BreezeSpeechService::voiceScore))
                    .map(v -> v.has("id") ? v.get("id").getAsString() : v.has("voice_id") ? v.get("voice_id").getAsString() : "")
                    .orElse("");
        } catch (Exception e) {
            System.err.println("[Breeze TTS] Voice discovery unavailable; configured voice ID can be supplied later.");
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static int voiceScore(JsonObject voice) {
        String all = voice.toString().toLowerCase(Locale.ROOT);
        int score = 0;
        if (all.contains("companion")) score += 10;
        if (all.contains("roleplay") || all.contains("role-play")) score += 8;
        if (all.contains("warm") || all.contains("friendly")) score += 5;
        if (all.contains("japanese")) score += 5;
        if (all.contains("female")) score += 3;
        if (all.contains("narrator") || all.contains("news")) score -= 6;
        return score;
    }

    private static String instructions(String style, String pitch) {
        String safeStyle = style == null || style.isBlank() ? "warm and conversational" : style;
        String safePitch = pitch == null || pitch.isBlank() ? "natural pitch" : pitch;
        return "Speak as Ciel, a warm female companion with a subtle Japanese accent. "
                + "Use natural human timing and breath, with a " + safeStyle + " delivery at " + safePitch
                + ". Preserve the supplied phonokana wording exactly; do not translate it or add a Japanese paragraph.";
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) if (read > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
