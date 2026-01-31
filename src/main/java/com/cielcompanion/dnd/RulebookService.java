package com.cielcompanion.dnd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RulebookService {

    private String srdContent = "";
    private String houseRulesContent = "";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String OPEN5E_API = "https://api.open5e.com/v1/";
    private static final String DND5E_API = "https://www.dnd5eapi.co/api/";

    public void initialize() {
        srdContent = loadResourceFile("/D&D_SRD.md");
        System.out.println("Ciel Debug (D&D): D&D 5e SRD loaded successfully into memory.");
        houseRulesContent = loadResourceFile("/house_rules.md");
        System.out.println("Ciel Debug (D&D): House Rules loaded successfully into memory.");
    }

    private String loadResourceFile(String path) {
        StringBuilder content = new StringBuilder();
        try (InputStream is = RulebookService.class.getResourceAsStream(path)) {
            if (is == null) {
                System.err.println("Ciel Error (D&D): Could not find resource file: " + path);
                return "";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
        } catch (IOException e) {
            System.err.println("Ciel Error (D&D): Failed to load resource file: " + path);
            e.printStackTrace();
        }
        return content.toString();
    }

    public String findRule(String topic) {
        // Layer 1: Search House Rules First
        String result = searchDocument(houseRulesContent, topic);
        if (result != null) {
            System.out.println("Ciel Debug (D&D): Found rule '" + topic + "' in house rules.");
            return result;
        }

        // Layer 2: Search Official SRD
        result = searchDocument(srdContent, topic);
        if (result != null) {
            System.out.println("Ciel Debug (D&D): Found rule '" + topic + "' in local SRD.");
            return result;
        }

        return null;
    }

    private String searchDocument(String content, String topic) {
        if (content.isEmpty()) return null;

        // Create a case-insensitive pattern for the topic as a heading
        Pattern pattern = Pattern.compile("^#+\\s+" + Pattern.quote(topic) + "\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            int start = matcher.end();
            // Find the start of the next section (another heading)
            Pattern nextSectionPattern = Pattern.compile("^#+", Pattern.MULTILINE);
            Matcher nextMatcher = nextSectionPattern.matcher(content);
            int end = content.length();
            if (nextMatcher.find(start)) {
                end = nextMatcher.start();
            }
            return content.substring(start, end).trim();
        }
        return null;
    }

    public String searchApi(String category, String query) {
        // Layer 3, Part 1: Try flexible search with Open5e
        String result = searchOpen5e(category, query);
        if (result != null) {
            System.out.println("Ciel Debug (D&D): Found API result for '" + query + "' via Open5e.");
            return result;
        }

        // Layer 3, Part 2: Try precise search with dnd5eapi as a fallback
        result = searchDnd5eApi(category, query);
        if (result != null) {
            System.out.println("Ciel Debug (D&D): Found API result for '" + query + "' via dnd5eapi fallback.");
            return result;
        }

        System.out.println("Ciel Debug (D&D): API search for '" + query + "' yielded no results.");
        return null;
    }

    private String searchOpen5e(String category, String query) {
        String formattedQuery = query.replace(" ", "+");
        String endpoint = getOpen5eEndpoint(category);
        if (endpoint == null) return null;

        String url = OPEN5E_API + endpoint + "/?search=" + formattedQuery;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                if (jsonResponse.has("results") && jsonResponse.getAsJsonArray("results").size() > 0) {
                    JsonObject firstResult = jsonResponse.getAsJsonArray("results").get(0).getAsJsonObject();
                    return formatOpen5eResult(firstResult, category);
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (D&D): Open5e API call failed: " + e.getMessage());
        }
        return null;
    }

    private String searchDnd5eApi(String category, String query) {
        String formattedQuery = query.toLowerCase().replace(" ", "-");
        String endpoint = getDnd5eApiEndpoint(category);
        if (endpoint == null) return null;

        String url = DND5E_API + endpoint + "/" + formattedQuery;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Accept", "application/json").build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
                return formatDnd5eApiResult(result, category);
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (D&D): dnd5eapi API call failed: " + e.getMessage());
        }
        return null;
    }
    
    // Helper methods to map categories to API endpoints
    private String getOpen5eEndpoint(String category) {
        return switch (category.toLowerCase()) {
            case "spell" -> "spells";
            case "monster" -> "monsters";
            case "magic item" -> "magicitems";
            case "feat" -> "feats";
            default -> null;
        };
    }

    private String getDnd5eApiEndpoint(String category) {
        return switch (category.toLowerCase()) {
            case "spell" -> "spells";
            case "monster" -> "monsters";
            case "magic item" -> "magic-items";
            case "feat" -> "feats";
            default -> null;
        };
    }

    // Helper methods to format JSON responses into speakable strings
    private String formatOpen5eResult(JsonObject result, String category) {
        if (category.equals("spell")) {
            return String.format("%s. Level %s %s. Casting time: %s. Range: %s. Description: %s",
                    result.get("name").getAsString(),
                    result.get("level").getAsString(),
                    result.get("school").getAsString(),
                    result.get("casting_time").getAsString(),
                    result.get("range").getAsString(),
                    result.get("desc").getAsString());
        }
        // Add formatting for other categories as needed
        return result.get("name").getAsString() + ". " + result.get("desc").getAsString();
    }
    
    private String formatDnd5eApiResult(JsonObject result, String category) {
        if (category.equals("spell")) {
            JsonArray descArray = result.get("desc").getAsJsonArray();
            List<String> descList = new ArrayList<>();
            for(JsonElement e : descArray) {
                descList.add(e.getAsString());
            }
            return String.format("%s. Level %s %s. Casting time: %s. Range: %s. Description: %s",
                    result.get("name").getAsString(),
                    result.get("level").getAsInt(),
                    result.get("school").getAsJsonObject().get("name").getAsString(),
                    result.get("casting_time").getAsString(),
                    result.get("range").getAsString(),
                    String.join(" ", descList));
        }
        // Add formatting for other categories as needed
        JsonArray descArray = result.get("desc").getAsJsonArray();
        List<String> descList = new ArrayList<>();
        for(JsonElement e : descArray) {
            descList.add(e.getAsString());
        }
        return result.get("name").getAsString() + ". " + String.join(" ", descList);
    }
}

