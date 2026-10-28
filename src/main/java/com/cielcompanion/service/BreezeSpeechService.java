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
import java.net.URLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.KeyStore;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
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
    private static volatile SSLSocketFactory windowsTrustSocketFactory;
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

    /**
     * Re-read the selected voice and provider settings after an external
     * profile edit. The next utterance uses the new voice; cached audio remains
     * safely namespaced by voice ID and settings.
     */
    public static void reloadConfiguration() {
        synchronized (INIT_LOCK) {
            initialized = false;
            available = false;
            apiKey = null;
            voiceId = null;
        }
        initialize();
    }

    public static boolean isAvailable() {
        return available;
    }

    public static boolean speak(String text, String key, String style, String pitch, String languageCode) {
        return speak(text, key, style, pitch, languageCode, true);
    }

    /** Generate/cache Breeze audio without playing it. Used to build a bounded
     * local Breeze library while Azure remains the audible primary provider. */
    public static boolean prefetch(String text, String key, String style, String pitch, String languageCode) {
        if (!Settings.isBreezePrefetchEnabled() || !Settings.isBreezeCacheEnabled()
                || key == null || key.isBlank()) return false;
        return speak(text, key, style, pitch, languageCode, false);
    }

    public static boolean isCached(String text, String key, String style, String pitch, String languageCode) {
        if (!Settings.isBreezeCacheEnabled() || text == null || text.isBlank()) return false;
        return findCachedPath(key, text, style, pitch, languageCode) != null
                || Files.isRegularFile(legacyCachePath(text, style, pitch, languageCode));
    }

    /** True once enough local Breeze WAVs exist to enable dynamic-speech mode. */
    public static boolean isWarmupComplete() {
        long target = Settings.getBreezeWarmupMinEntries();
        if (target <= 0) return true;
        try (java.util.stream.Stream<Path> files = Files.list(CACHE_DIR)) {
            String prefix = ("breeze_" + safeToken(Settings.getBreezeModel()) + "_"
                    + safeToken(voiceId) + "_").toLowerCase(Locale.ROOT);
            return files.filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith(prefix) && name.endsWith(".wav");
                    })
                    .limit(target).count() >= target;
        } catch (Exception ignored) { return false; }
    }

    private static boolean speak(String text, String key, String style, String pitch, String languageCode,
                                 boolean playAudio) {
        if (!available || text == null || text.isBlank()) return false;
        Path cached = cachePath(key, text, style, pitch, languageCode);
        Path reusable = findCachedPath(key, text, style, pitch, languageCode);
        if (Settings.isBreezeCacheEnabled() && reusable != null) {
            if (!playAudio) return true;
            boolean played = AzureSpeechService.playProviderWavFile(reusable.toFile(), key == null ? "BreezeCached" : key,
                    Settings.isBreezePrimeOutput());
            if (played) System.out.println("[Breeze TTS] Cache hit; no credits consumed.");
            return played;
        }
        Path legacy = legacyCachePath(text, style, pitch, languageCode);
        if (Settings.isBreezeCacheEnabled() && Files.isRegularFile(legacy)) {
            if (!playAudio) return true;
            boolean played = AzureSpeechService.playProviderWavFile(legacy.toFile(), key == null ? "BreezeLegacyCached" : key,
                    Settings.isBreezePrimeOutput());
            if (played) System.out.println("[Breeze TTS] Legacy cache hit; no credits consumed.");
            return played;
        }
        if (!BreezeUsageTracker.reserve(text)) return false;
        HttpURLConnection connection = null;
        Path temp = null;
        try {
            URI uri = URI.create(API_ROOT + "/v1/text-to-speech/" +
                    URLEncoder.encode(voiceId, StandardCharsets.UTF_8).replace("+", "%20") + "?output_format=wav");
            connection = openConnection(uri);
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
            recordCache(cached, key, text, languageCode);
            if (!playAudio) return true;
            Path playable = Settings.isBreezeCacheEnabled() ? cached : temp;
            boolean played = AzureSpeechService.playProviderWavFile(playable.toFile(), key == null ? "BreezeDynamic" : key,
                    Settings.isBreezePrimeOutput());
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

    private static Path cachePath(String key, String text, String style, String pitch, String languageCode) {
        String material = key + "\n" + voiceId + "\n" + Settings.getBreezeModel() + "\n"
                + Settings.getBreezeLanguageCode() + "\n" + languageCode + "\n"
                + style + "\n" + pitch + "\n" + text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            String prefix = "breeze_" + safeToken(Settings.getBreezeModel()) + "_"
                    + safeToken(voiceId) + "_" + safeToken(key) + "_" + safeToken(style);
            return CACHE_DIR.resolve(prefix + "_" + hex + ".wav");
        } catch (Exception e) {
            return CACHE_DIR.resolve("breeze_" + safeToken(Settings.getBreezeModel()) + "_"
                    + safeToken(voiceId) + "_" + safeToken(key) + "_" + safeToken(style) + "_"
                    + Integer.toHexString(material.hashCode()) + ".wav");
        }
    }

    /** Previous cache naming, retained so existing audio remains usable. */
    private static Path priorCachePath(String key, String text, String style, String pitch, String languageCode) {
        String material = key + "\n" + voiceId + "\n" + Settings.getBreezeModel() + "\n"
                + Settings.getBreezeLanguageCode() + "\n" + languageCode + "\n"
                + style + "\n" + pitch + "\n" + text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            String prefix = "breeze_" + safeToken(Settings.getBreezeModel()) + "_"
                    + safeToken(voiceId) + "_" + safeToken(key);
            return CACHE_DIR.resolve(prefix + "_" + hex + ".wav");
        } catch (Exception e) {
            return CACHE_DIR.resolve("breeze_" + safeToken(Settings.getBreezeModel()) + "_"
                    + safeToken(voiceId) + "_" + safeToken(key) + "_" + Integer.toHexString(material.hashCode()) + ".wav");
        }
    }

    /**
     * Fixed dialogue keys include a content fingerprint in the key.  Their
     * cache filename also varies with pitch/style, so human-variance changes
     * must not spend another Breeze credit for the same sentence.  Dynamic
     * keys are intentionally excluded because they do not identify their text.
     */
    private static Path findCachedPath(String key, String text, String style, String pitch, String languageCode) {
        if (!Settings.isBreezeCacheEnabled() || key == null || key.isBlank()) {
            return null;
        }
        Path exact = cachePath(key, text, style, pitch, languageCode);
        if (Files.isRegularFile(exact)) return exact;
        Path prior = priorCachePath(key, text, style, pitch, languageCode);
        if (Files.isRegularFile(prior)) return prior;
        if (key.equals("media_commentary") || key.equals("game_launch") || key.startsWith("dynamic")) {
            return null;
        }
        String prefix = "breeze_" + safeToken(Settings.getBreezeModel()) + "_"
                + safeToken(voiceId) + "_" + safeToken(key) + "_" + safeToken(style) + "_";
        try (java.util.stream.Stream<Path> files = Files.list(CACHE_DIR)) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)
                            && path.getFileName().toString().endsWith(".wav"))
                    .max(java.util.Comparator.comparingLong(path -> {
                        try { return Files.getLastModifiedTime(path).toMillis(); }
                        catch (Exception ignored) { return Long.MIN_VALUE; }
                    }))
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path legacyCachePath(String text, String style, String pitch, String languageCode) {
        String material = voiceId + "\n" + Settings.getBreezeModel() + "\n"
                + Settings.getBreezeLanguageCode() + "\n" + languageCode + "\n"
                + style + "\n" + pitch + "\n" + text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return CACHE_DIR.resolve(hex + ".wav");
        } catch (Exception e) {
            return CACHE_DIR.resolve(Integer.toHexString(material.hashCode()) + ".wav");
        }
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) return "default";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void recordCache(Path file, String key, String text, String languageCode) {
        try {
            Files.createDirectories(CACHE_DIR);
            Path index = CACHE_DIR.resolve("index.jsonl");
            JsonObject row = new JsonObject();
            row.addProperty("provider", "breeze");
            row.addProperty("model", Settings.getBreezeModel());
            row.addProperty("voice_id", voiceId);
            row.addProperty("key", key == null ? "" : key);
            row.addProperty("language_code", languageCode == null ? "" : languageCode);
            row.addProperty("file", file.toString());
            row.addProperty("characters", text == null ? 0 : text.length());
            row.addProperty("created_at_ms", System.currentTimeMillis());
            synchronized (CACHE_DIR.toString().intern()) {
                Files.writeString(index, row + System.lineSeparator(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (Exception ignored) {
            // Cache metadata is advisory; never turn successful speech into a failure.
        }
    }

    /**
     * Windows security products can terminate TLS with a locally generated
     * certificate. Java's bundled cacerts does not know that Windows root,
     * while the OS/browser trust store does. Use Windows-ROOT for Breeze
     * connections when available; on non-Windows or restricted JVMs the
     * normal Java trust configuration remains the safe fallback.
     */
    private static HttpURLConnection openConnection(URI uri) throws Exception {
        URLConnection raw = uri.toURL().openConnection();
        if (raw instanceof HttpsURLConnection https) {
            SSLSocketFactory factory = windowsTrustSocketFactory();
            if (factory != null) https.setSSLSocketFactory(factory);
        }
        return (HttpURLConnection) raw;
    }

    private static SSLSocketFactory windowsTrustSocketFactory() {
        SSLSocketFactory cached = windowsTrustSocketFactory;
        if (cached != null) return cached;
        try {
            KeyStore roots = KeyStore.getInstance("Windows-ROOT");
            roots.load(null, null);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trust.init(roots);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            cached = context.getSocketFactory();
            windowsTrustSocketFactory = cached;
            System.out.println("[Breeze TTS] Using Windows trusted roots for HTTPS.");
            return cached;
        } catch (Exception ignored) {
            // Keep the JVM's normal trust store if Windows-ROOT is unavailable.
            return null;
        }
    }

    private static String discoverVoice() {
        HttpURLConnection connection = null;
        try {
            String query = "search=" + URLEncoder.encode("female Japanese roleplay companion", StandardCharsets.UTF_8)
                    + "&language_code=en&gender=female&accent=japanese&page_size=50";
            connection = openConnection(URI.create(API_ROOT + "/v1/voices?" + query));
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
        String persona = Settings.getCompanionPersona();
        String name = Settings.getCompanionName();
        String custom = Settings.getTtsInstructions();
        String base = custom == null || custom.isBlank()
                ? "Speak as " + name + ", a " + persona + " with a subtle Japanese accent. "
                : custom.trim() + " ";
        return base
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
