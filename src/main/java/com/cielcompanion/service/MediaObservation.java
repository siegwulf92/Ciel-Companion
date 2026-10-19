package com.cielcompanion.service;

import java.util.*;
import java.util.regex.*;

/** Pure protocol helpers: UI text is evidence, never authoritative metadata. */
public final class MediaObservation {
    private MediaObservation() {}
    private static final Set<String> IDENTITY = Set.of("SERIES", "EPISODE", "MEDIA_KIND",
            "MEDIA_TITLE", "DESCRIPTION", "METADATA_STATUS", "METADATA_SOURCES",
            "SERIES_DESCRIPTION", "SEASON", "EPISODE_NUMBER");

    public static String field(String dom, String key) {
        if (dom == null) return "";
        String header = dom.split("--- ON-SCREEN UI TEXT ---", 2)[0];
        Matcher match = Pattern.compile("(?mi)^" + Pattern.quote(key) + ":[ \\t]*([^\\r\\n]*)$").matcher(header);
        return match.find() ? match.group(1).trim() : "";
    }

    public static boolean verified(String dom) {
        if ("VIDEO".equalsIgnoreCase(field(dom, "MEDIA_KIND"))
                && "PUBLISHER".equalsIgnoreCase(field(dom, "METADATA_STATUS"))) {
            return "youtube_publisher".equals(field(dom, "METADATA_SOURCES"))
                    && !field(dom, "MEDIA_TITLE").isBlank()
                    && !field(dom, "DESCRIPTION").isBlank();
        }
        if (!"VERIFIED".equalsIgnoreCase(field(dom, "METADATA_STATUS"))) return false;
        if ("FILM".equalsIgnoreCase(field(dom, "MEDIA_KIND"))) return !field(dom, "MEDIA_TITLE").isBlank();
        return !field(dom, "EPISODE").isBlank() && !field(dom, "SERIES").isBlank()
                && !field(dom, "SERIES").equalsIgnoreCase("Unknown");
    }

    public static String merge(String previous, String incoming, boolean metadataUpdate) {
        if (incoming == null || incoming.equals("NO_CHANGE")) return previous;
        // Only a complete verified observation may replace a verified identity.
        boolean preserve = verified(previous) && !(metadataUpdate && verified(incoming));
        if (!preserve) return metadataUpdate ? incoming : (previous == null ? "" : previous);
        StringBuilder merged = new StringBuilder();
        for (String line : incoming.split("\\R")) {
            int colon = line.indexOf(':');
            String key = colon < 0 ? "" : line.substring(0, colon).trim();
            if (line.contains("--- ON-SCREEN UI TEXT ---")) break;
            if (!IDENTITY.contains(key)) merged.append(line).append('\n');
        }
        for (String key : IDENTITY) merged.append(key).append(": ").append(field(previous, key)).append('\n');
        int ui = incoming.indexOf("--- ON-SCREEN UI TEXT ---");
        if (ui >= 0) merged.append(incoming.substring(ui));
        return merged.toString();
    }

    public static boolean freshPlayback(String state, long evidenceAt, long now) {
        return !"PAUSED".equals(state) && !"UNAVAILABLE".equals(state)
                && evidenceAt > 0 && now >= evidenceAt && now - evidenceAt <= 15_000;
    }

    public static int commentaryTargetSeconds(boolean film, int runtimeMinutes) {
        if (runtimeMinutes <= 0) return film ? -1 : 300;
        int total = runtimeMinutes * 60;
        int latest = total > 180 ? total - 180 : Math.max(1, total * 4 / 5);
        if (film) return latest;
        // Short episodes: around five minutes, before the end of very short videos.
        // Longer episodes: about 15% in, bounded at five to fifteen minutes.
        return Math.min(latest,
                Math.max(300, Math.min(900, total * 15 / 100)));
    }

    public static boolean commentaryTimeReached(int positionSeconds, int confirmedMinutes, int targetSeconds) {
        return commentaryTimeReached(positionSeconds, confirmedMinutes, targetSeconds, -1);
    }

    public static boolean commentaryTimeReached(int positionSeconds, int confirmedMinutes, int targetSeconds, int runtimeMinutes) {
        if (targetSeconds < 0) return false;
        if (runtimeMinutes > 0 && (positionSeconds >= runtimeMinutes * 60
                || (positionSeconds < 0 && confirmedMinutes >= runtimeMinutes))) return false;
        return positionSeconds >= 0 ? positionSeconds >= targetSeconds : confirmedMinutes * 60 >= targetSeconds;
    }
}
