package com.cielcompanion.util;

import java.util.Map;

public class PhoneticConverter {

    private static final Map<String, String> IPA_TO_KATAKANA = Map.ofEntries(
            Map.entry("ə", "ア"), Map.entry("ʌ", "ア"), Map.entry("ɑ", "ア"), Map.entry("æ", "ア"),
            Map.entry("e", "エ"), Map.entry("ɛ", "エ"), Map.entry("ɪ", "イ"), Map.entry("i", "イ"),
            Map.entry("o", "オ"), Map.entry("ɔ", "オ"), Map.entry("ʊ", "ウ"), Map.entry("u", "ウ"),
            Map.entry("aɪ", "アイ"), Map.entry("aʊ", "アウ"), Map.entry("ɔɪ", "オイ"), Map.entry("eɪ", "エイ"),
            Map.entry("oʊ", "オウ"), Map.entry("ju", "ユ"),
            Map.entry("p", "プ"), Map.entry("b", "ブ"), Map.entry("t", "ト"), Map.entry("d", "ド"),
            Map.entry("k", "ク"), Map.entry("ɡ", "グ"), Map.entry("f", "フ"), Map.entry("v", "ヴ"),
            Map.entry("θ", "ス"), Map.entry("ð", "ズ"), Map.entry("s", "ス"), Map.entry("z", "ズ"),
            Map.entry("ʃ", "シュ"), Map.entry("ʒ", "ジュ"), Map.entry("h", "ハ"), Map.entry("m", "ム"),
            Map.entry("n", "ン"), Map.entry("ŋ", "ング"), Map.entry("l", "ル"), Map.entry("r", "ル"),
            Map.entry("ɹ", "ル"), Map.entry("w", "ウ"), Map.entry("j", "ヤ"),
            Map.entry("tʃ", "チ"), Map.entry("dʒ", "ジ"),
            // --- UPDATED & EXPANDED ENTRIES ---
            Map.entry("ɒ", "オ"),   // For sounds like in "song" or "from"
            Map.entry("ɜ", "ア"),   // For sounds like in "purple"
            Map.entry("ʍ", "ホ"),   // For sounds like in "when"
            Map.entry("ː", "ー"),   // The long vowel sound extender
            Map.entry("ɚ", "アー"),  // For sounds like in "butter"
            Map.entry("ɾ", "ラ"),   // For the flap sound in "butter"
            // --- END OF UPDATED ENTRIES ---
            Map.entry("ˈ", ""), // Primary stress (ignored)
            Map.entry("ˌ", "")  // Secondary stress (ignored)
    );

    public static String ipaToKatakana(String ipa) {
        if (ipa == null || ipa.isBlank()) {
            return null;
        }

        String cleanIpa = ipa.replaceAll("[/\\[\\]()̯.]", "").trim();
        StringBuilder katakana = new StringBuilder();
        
        int i = 0;
        while (i < cleanIpa.length()) {
            if (i + 1 < cleanIpa.length()) {
                String twoCharSymbol = cleanIpa.substring(i, i + 2);
                if (IPA_TO_KATAKANA.containsKey(twoCharSymbol)) {
                    katakana.append(IPA_TO_KATAKANA.get(twoCharSymbol));
                    i += 2;
                    continue;
                }
            }
            String oneCharSymbol = cleanIpa.substring(i, i + 1);
            if (IPA_TO_KATAKANA.containsKey(oneCharSymbol)) {
                katakana.append(IPA_TO_KATAKANA.get(oneCharSymbol));
            } else {
                 System.out.println("Ciel Debug (Phonetics): Unknown IPA symbol encountered and skipped: " + oneCharSymbol);
            }
            i++;
        }
        return katakana.toString();
    }
}
