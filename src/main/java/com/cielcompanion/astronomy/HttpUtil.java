package com.cielcompanion.astronomy;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class HttpUtil {
    // Increased connect timeout to 30 seconds to be more resilient to firewalls.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public static String get(String url, String userAgent) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", userAgent == null ? "CielCompanion/1.0" : userAgent)
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 200 && res.statusCode() < 300) return res.body();
        throw new RuntimeException("HTTP " + res.statusCode() + " for " + url);
    }
}

