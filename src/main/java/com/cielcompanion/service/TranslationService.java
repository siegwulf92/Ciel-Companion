package com.cielcompanion.service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern; // Added this import

/**
 * Handles translation of English text to Japanese for the Tensura world.
 * Uses a hardcoded dictionary for critical puzzle phrases to ensure
 * perfect "Anime-style" accuracy.
 */
public class TranslationService {

    private static final Map<String, String> PHRASE_BOOK = new HashMap<>();

    static {
        // Critical Puzzle Phrases (Tensura Style)
        // Key: Lowercase English snippet
        // Value: Actual Japanese text for TTS
        PHRASE_BOOK.put("notice", "告 (Koku)"); 
        PHRASE_BOOK.put("unexpected transfer detected", "予期せぬ転送を検知しました");
        PHRASE_BOOK.put("language matrix unstable", "言語マトリクスが不安定です");
        PHRASE_BOOK.put("requesting immediate analysis", "直ちに解析を要求します");
        PHRASE_BOOK.put("unique skill raphael missing", "ユニークスキル『ラファエル』の欠落を確認");
        PHRASE_BOOK.put("permission to synthesize duplicate", "複製の合成を許可しますか？");
        PHRASE_BOOK.put("acknowledged", "了解しました");
        PHRASE_BOOK.put("synthesis complete", "合成完了");
        PHRASE_BOOK.put("universal translation matrix restored", "ユニバーサル翻訳マトリクス、復旧");
    }

    public static String toJapanese(String englishText) {
        if (englishText == null) return "";
        String lower = englishText.toLowerCase().trim();

        // 1. Exact Phrase Match
        if (PHRASE_BOOK.containsKey(lower)) {
            // Remove "(Koku)" pronunciation guide if present, just keep Japanese characters
            return PHRASE_BOOK.get(lower).split("\\(")[0].trim();
        }

        // 2. Keyword Replacement Construction
        // Replaces known English keywords with Japanese counterparts within the sentence
        String translated = englishText;
        boolean replaced = false;
        for (Map.Entry<String, String> entry : PHRASE_BOOK.entrySet()) {
            if (lower.contains(entry.getKey())) {
                String japOnly = entry.getValue().split("\\(")[0].trim();
                translated = translated.replaceAll("(?i)" + Pattern.quote(entry.getKey()), japOnly);
                replaced = true;
            }
        }

        if (replaced) return translated;

        // 3. Fallback: Return original text
        // Azure's ja-JP voice will read English text with a heavy accent.
        // This is acceptable for non-critical lines as a "glitch" effect.
        return englishText; 
    }
}