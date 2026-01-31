package com.cielcompanion.service;

import com.cielcompanion.util.PhoneticConverter;
import com.cielcompanion.util.PhonoKana;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PhoneticsService {

    private static final Properties phoneticCache = new Properties();
    private static final String APP_DATA_DIRECTORY = System.getenv("LOCALAPPDATA") + File.separator + "CielCompanion";
    private static final Path CACHE_FILE_PATH = Paths.get(APP_DATA_DIRECTORY, "phonokana_exceptions.properties");
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ExecutorService apiExecutor = Executors.newSingleThreadExecutor();

    public static void initialize() {
        try {
            Files.createDirectories(CACHE_FILE_PATH.getParent());
            if (Files.exists(CACHE_FILE_PATH)) {
                try (InputStreamReader reader = new InputStreamReader(new FileInputStream(CACHE_FILE_PATH.toFile()), StandardCharsets.UTF_8)) {
                    phoneticCache.load(reader);
                    System.out.println("Ciel Debug: Loaded " + phoneticCache.size() + " phonetic exceptions from external cache.");
                }
            } else {
                 System.out.println("Ciel Debug: No external phonetic cache found. It will be created when the first new word is learned.");
            }
        } catch (IOException e) {
            System.err.println("Ciel Error: Could not load phonetic cache file from " + CACHE_FILE_PATH);
            e.printStackTrace();
        }
    }

    public static String processSentence(String sentence) {
        StringBuilder result = new StringBuilder();
        Pattern pattern = Pattern.compile("([a-zA-Z]+)|([^a-zA-Z]+)");
        Matcher matcher = pattern.matcher(sentence);

        while (matcher.find()) {
            if (matcher.group(1) != null) { // It's a word
                String word = matcher.group(1);
                result.append(getKatakanaPronunciation(word));
            } else if (matcher.group(2) != null) { // It's a delimiter (space, punctuation, etc.)
                result.append(matcher.group(2));
            }
        }
        return result.toString();
    }

    private static String getKatakanaPronunciation(String word) {
        String lowerWord = word.toLowerCase();
        if (phoneticCache.containsKey(lowerWord)) {
            return phoneticCache.getProperty(lowerWord);
        }

        // Fallback for when API call is not needed or fails
        return PhonoKana.getInstance().toKatakana(word);
    }
    
    private static String findBestPhonetic(JsonObject entry) {
        if (entry.has("phonetic") && !entry.get("phonetic").isJsonNull()) {
            return entry.get("phonetic").getAsString();
        }
        if (entry.has("phonetics")) {
            JsonArray phonetics = entry.getAsJsonArray("phonetics");
            for (JsonElement element : phonetics) {
                JsonObject phoneticObject = element.getAsJsonObject();
                if (phoneticObject.has("text") && !phoneticObject.get("text").isJsonNull()) {
                    return phoneticObject.get("text").getAsString();
                }
            }
        }
        return null;
    }

    private static void saveNewPronunciation(String word, String katakana) {
        phoneticCache.setProperty(word, katakana);
        apiExecutor.submit(() -> {
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(CACHE_FILE_PATH.toFile()), StandardCharsets.UTF_8)) {
                phoneticCache.store(writer, "Ciel Companion - Phonetic Cache (Auto-generated)");
            } catch (IOException e) {
                System.err.println("Ciel Error: Failed to save updated phonetic cache to " + CACHE_FILE_PATH);
                e.printStackTrace();
            }
        });
    }
}
