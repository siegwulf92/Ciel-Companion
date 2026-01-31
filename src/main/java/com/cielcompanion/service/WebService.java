package com.cielcompanion.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WebService {

    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    private static final Set<String> OPINION_KEYWORDS = Set.of("best", "opinion", "think", "latest", "trending");
    private static final String USER_AGENT = "CielCompanion/1.0 (https://github.com/user/repo; mail@example.com) Java-HttpClient/21";
    
    // REWORKED: Expanded stop words and added junk phrases for better keyword extraction.
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "is", "are", "was", "were", "in", "of", "to", "and", "i", "you", "it", "for", "from");
    private static final List<String> JUNK_PHRASES = List.of("who was the first", "what is the chemical element for", "who directed the movie", "what is the theory of", "what is the capital of", "who directed the", "what is the");

    private boolean isDynamicOrOpinionQuery(String query) {
        String lowerQuery = query.toLowerCase();
        return OPINION_KEYWORDS.stream().anyMatch(lowerQuery::contains);
    }

    private List<String> extractKeywords(String query) {
        String lowerQuery = query.toLowerCase().replace("?", "");
        
        for (String junk : JUNK_PHRASES) {
            if (lowerQuery.startsWith(junk)) {
                lowerQuery = lowerQuery.substring(junk.length()).trim();
            }
        }
        
        return Arrays.stream(lowerQuery.split("\\s+"))
            .filter(word -> !STOP_WORDS.contains(word))
            .collect(Collectors.toList());
    }
    
    /**
     * REWORKED: Now sanitizes raw text from the web more aggressively, removing citations,
     * and parenthetical remarks to create a cleaner, more speakable sentence.
     */
    private String sanitizeForSpeech(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // Remove citations like [1], [2], etc.
        String noCitations = text.replaceAll("\\[[0-9]+]", "");
        // Remove anything in parentheses
        String noParentheses = noCitations.replaceAll("\\(.*?\\)", "");
        // Remove URLs
        String noUrls = noParentheses.replaceAll("https?://\\S+\\s?", "");
        // Remove special characters, leaving sentence structure
        String cleaned = noUrls.replaceAll("[^a-zA-Z0-9.,'\"\\s-]", "");
        // Normalize whitespace
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        
        // Truncate to the first two sentences to avoid long, rambling responses.
        String[] sentences = cleaned.split("(?<=[.!?])\\s*");
        return Stream.of(sentences).limit(2).collect(Collectors.joining(" ")).trim();
    }


    private Optional<String> findWikipediaAnswer(String query) {
        try {
            List<String> keywords = extractKeywords(query);
            if (keywords.isEmpty()) {
                System.out.println("Ciel Debug (WebService-Wiki): No keywords extracted from query.");
                return Optional.empty();
            }

            String searchSubject = String.join(" ", keywords);

            Optional<String> articleTitleOpt = searchForWikipediaArticle(searchSubject);
            if (articleTitleOpt.isEmpty()) {
                System.out.println("Ciel Debug (WebService-Wiki): Could not find a relevant article title for subject: " + searchSubject);
                // Fallback: Try the original query if keyword extraction fails
                articleTitleOpt = searchForWikipediaArticle(query.toLowerCase().replace("?", "").trim());
                if (articleTitleOpt.isEmpty()) {
                    return Optional.empty();
                }
            }

            String articleTitle = articleTitleOpt.get();
            System.out.println("Ciel Debug (WebService-Wiki): Found top article title: " + articleTitle);

            String articleText = getWikipediaArticleText(articleTitle);
            if (articleText.isEmpty()) return Optional.empty();

            String bestSentence = "";
            long bestScore = 0;

            for (String sentence : articleText.split("(?<!\\b[A-Z][a-z])(?<![A-Z])\\.\\s+")) {
                String lowerSentence = sentence.toLowerCase();
                long currentScore = keywords.stream().filter(lowerSentence::contains).count();

                if (currentScore > bestScore) {
                    bestScore = currentScore;
                    bestSentence = sentence;
                }
            }

            if (bestScore >= Math.max(1, keywords.size() - 1) && !bestSentence.isBlank()) {
                System.out.println("Ciel Debug (WebService-Wiki): Found best matching sentence with score " + bestScore + ": " + bestSentence);
                return Optional.of(bestSentence.trim() + ".");
            }

        } catch (Exception e) {
            System.err.println("Ciel Error (WebService): Failed during Wikipedia processing.");
            e.printStackTrace();
        }
        System.out.println("Ciel Debug (WebService-Wiki): No keyword-matching sentence found.");
        return Optional.empty();
    }

    private Optional<String> searchForWikipediaArticle(String query) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String searchUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&search=" + encodedQuery + "&limit=1&namespace=0&format=json";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(searchUrl))
                .header("User-Agent", USER_AGENT)
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray jsonResponse = JsonParser.parseString(response.body()).getAsJsonArray();
            JsonArray titles = jsonResponse.get(1).getAsJsonArray();
            if (titles.size() > 0) {
                return Optional.of(titles.get(0).getAsString());
            }
        }
        return Optional.empty();
    }

    private String getWikipediaArticleText(String articleTitle) throws Exception {
        String encodedTitle = URLEncoder.encode(articleTitle, StandardCharsets.UTF_8);
        String url = "https://en.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&explaintext&redirects=1&titles=" + encodedTitle;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("User-Agent", USER_AGENT)
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject pages = root.getAsJsonObject("query").getAsJsonObject("pages");
            Optional<Map.Entry<String, JsonElement>> firstPage = pages.entrySet().stream().findFirst();

            if (firstPage.isPresent() && !firstPage.get().getKey().equals("-1")) {
                JsonElement extractElement = firstPage.get().getValue().getAsJsonObject().get("extract");
                if (extractElement != null && !extractElement.isJsonNull()) {
                    return extractElement.getAsString();
                }
            }
        }
        return "";
    }

    private void openBrowserSearch(String query) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.bing.com/search?q=" + encodedQuery;
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            System.err.println("Ciel Error (WebService): Failed to open browser for search.");
        }
    }

    public void answerQuestion(String query) {
        if (isDynamicOrOpinionQuery(query)) {
            System.out.println("Ciel Debug: Detected dynamic/opinion query. Opening browser.");
            LineManager.getWebSearchFallbackLine().ifPresent(line -> SpeechService.speak(line.text()));
            openBrowserSearch(query);
            return;
        }

        Optional<String> wikipediaAnswer = findWikipediaAnswer(query);
        if(wikipediaAnswer.isPresent()){
            SpeechService.speak(sanitizeForSpeech(wikipediaAnswer.get()));
            return;
        }

        LineManager.getWebSearchFallbackLine().ifPresent(line -> SpeechService.speak(line.text()));
        openBrowserSearch(query);
    }
}

