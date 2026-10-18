package com.cielcompanion.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure text preparation; no inference, playback, or changes to the speaker wake cue. */
public final class SpeechText {
    private SpeechText() {}

    public static String normalizeSource(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replace('’', '\'')
                .replaceAll("(?<=[A-Za-z])\\.{2,}(?=[A-Za-z])", "")
                .replaceAll("\\.{2,}", ", ")
                .replaceAll("\\s+", " ").trim();
    }

    public static String normalizeKana(String text) {
        String value = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            out.append(switch (c) {
                case ',', ';', ':' -> '、';
                case '.' -> '。';
                case '?' -> '？';
                case '!' -> '！';
                default -> c;
            });
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    public static boolean isValidKana(String text) {
        return text != null && !text.isBlank()
                && !Pattern.compile("[A-Za-z]").matcher(text).find()
                && Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]").matcher(text).find();
    }

    public static String contextualRead(String text) {
        Matcher matches = Pattern.compile("\\bread\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        StringBuilder out = new StringBuilder();
        while (matches.find()) {
            String before = text.substring(0, matches.start()).toLowerCase(Locale.ROOT).replaceAll("(?s)^.*[,;.!?。？！]", "");
            String after = text.substring(matches.end()).toLowerCase(Locale.ROOT).split("[,;.!?。？！]", 2)[0];
            boolean perfect = before.matches("(?s).*\\b(?:have|has|had)(?:\\s+(?:i|you|he|she|it|we|they))?(?:\\s+(?:already|just|never|ever|recently|previously|carefully|not)){0,3}\\s*");
            boolean base = before.matches("(?s).*\\b(?:will|shall|can|could|should|would|must|may|might|to|please|do|does|did|don't|doesn't|didn't)(?:\\s+(?:i|you|he|she|it|we|they))?(?:\\s+(?:not|always|usually|carefully|just)){0,2}\\s*");
            boolean past = Pattern.compile("\\b(?:yesterday|previously|earlier|ago|last\\s+(?:night|week|month|year))\\b").matcher(before + " " + after).find();
            matches.appendReplacement(out, perfect || (!base && past) ? "レッド" : "リード");
        }
        matches.appendTail(out);
        return out.toString();
    }

    public static String localKatakana(String source) {
        // Keep contextual homographs, but use dictionary/phoneme conversion.
        // Never spell a failed English word letter by letter.
        return normalizeKana(com.cielcompanion.ai.AIEngine.transliterateToKatakanaSync(
                contextualRead(normalizeSource(source))));
    }

    public static String cacheKey(String key, String source, String lang) {
        if (key == null || !"ja-JP".equalsIgnoreCase(lang) || !Pattern.compile("[A-Za-z]").matcher(source).find()) return key;
        if (key.isBlank() || key.equals("game_launch") || key.equals("media_commentary") || key.startsWith("dynamic")) return key;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizeSource(source).getBytes(StandardCharsets.UTF_8));
            return key + ".phonokana2." + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("Speech cache fingerprint unavailable", e);
        }
    }
}
