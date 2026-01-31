package com.cielcompanion.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

public final class PhonoKana {

    private final Map<String, String> exceptions;
    private final Map<Pattern, String> rules;
    private final Map<String, String> letterNames;

    private PhonoKana(Map<String, String> exceptions, Map<Pattern, String> rules, Map<String, String> letterNames) {
        this.exceptions = exceptions;
        this.rules = rules;
        this.letterNames = letterNames;
    }

    public static boolean isKatakana(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.KATAKANA) {
                return true;
            }
        }
        return false;
    }

    public String toKatakana(String input) {
        if (input == null || input.isBlank()) return "";
        
        String normalizedInput = Normalizer.normalize(input, Normalizer.Form.NFD)
                                           .replaceAll("\\p{M}", "");

        String preprocessedInput = expandRomanNumerals(normalizedInput);
        String s = Normalizer.normalize(preprocessedInput, Normalizer.Form.NFC);

        List<Token> tokens = tokenize(s);
        StringBuilder out = new StringBuilder();

        for (Token t : tokens) {
            if (out.length() > 0 && t.type != Type.OTHER) {
                out.append(" ");
            }
            
            String k = switch (t.type) {
                case WORD -> wordToKatakana(t.text);
                case NUMBER -> EnglishNumber.convert(t.text);
                case ACRONYM -> spellAcronym(t.text);
                default -> t.text;
            };
            out.append(k);
        }
        return out.toString().trim();
    }
    
    private String expandRomanNumerals(String s) {
        // CORRECTED: The rule for a single "I" has been removed to prevent ambiguity.
        return s.replaceAll("\\bX-?2\\b", " Ten Two")
                .replaceAll("\\bX-?II\\b", " Ten Two")
                .replaceAll("\\bXIII\\b", " Thirteen")
                .replaceAll("\\bXII\\b", " Twelve")
                .replaceAll("\\bXI\\b", " Eleven")
                .replaceAll("\\bX\\b", " Ten")
                .replaceAll("\\bIX\\b", " Nine")
                .replaceAll("\\bVIII\\b", " Eight")
                .replaceAll("\\bVII\\b", " Seven")
                .replaceAll("\\bVI\\b", " Six")
                .replaceAll("\\bV\\b", " Five")
                .replaceAll("\\bIV\\b", " Four")
                .replaceAll("\\bIII\\b", " Three")
                .replaceAll("\\bII\\b", " Two");
    }

    private String wordToKatakana(String w) {
        String lower = w.toLowerCase(Locale.ROOT);
        if (exceptions.containsKey(lower)) {
            return exceptions.get(lower);
        }
        String romajiLike = applyRules(lower);
        return romajiLikeToKatakana(romajiLike);
    }

    private String applyRules(String s) {
        String out = " " + s + " ";
        for (var e : rules.entrySet()) {
            out = e.getKey().matcher(out).replaceAll(e.getValue());
        }
        out = out.replace('l', 'r');
        out = out.replaceAll("([bcdfghjklmnpqrstvwxyz])\\1", "$1");
        return out.trim();
    }

    private String romajiLikeToKatakana(String r) {
        r = r.replaceAll("(?<![aiueo])(k)([aiueo])", "ク$2");
        return r.replace("shon", "ション")
                .replace("jon", "ジョン")
                .replace("chaa", "チャー")
                .replace("jaa", "ジャー")
                .replace("va", "ヴァ").replace("vi", "ヴィ").replace("ve", "ヴェ").replace("vo", "ヴォ")
                .replace("fa", "ファ").replace("fi", "フィ").replace("fe", "フェ").replace("fo", "フォ")
                .replace("ti", "ティ").replace("di", "ディ")
                .replace("tu", "テュ").replace("du", "デュ")
                .replace("shi", "シ").replace("chi", "チ").replace("tsu", "ツ")
                .replace("kya", "キャ").replace("kyu", "キュ").replace("kyo", "キョ")
                .replace("sha", "シャ").replace("shu", "シュ").replace("sho", "ショ")
                .replace("ai", "アイ").replace("a", "ア").replace("i", "イ")
                .replace("u", "ウ").replace("e", "エ").replace("o", "オ")
                .replace("ka", "カ").replace("ki", "キ").replace("ku", "ク").replace("ke", "ケ").replace("ko", "コ")
                .replace("sa", "サ").replace("su", "ス").replace("se", "セ").replace("so", "ソ")
                .replace("ta", "タ").replace("te", "テ").replace("to", "ト")
                .replace("na", "ナ").replace("ni", "ニ").replace("nu", "ヌ").replace("ne", "ネ").replace("no", "ノ")
                .replace("ha", "ハ").replace("hi", "ヒ").replace("fu", "フ").replace("he", "ヘ").replace("ho", "ホ")
                .replace("ma", "マ").replace("mi", "ミ").replace("mu", "ム").replace("me", "メ").replace("mo", "モ")
                .replace("ya", "ヤ").replace("yu", "ユ").replace("yo", "ヨ")
                .replace("ra", "ラ").replace("ri", "リ").replace("ru", "ル").replace("re", "レ").replace("ro", "ロ")
                .replace("wa", "ワ").replace("wo", "ヲ").replace("n", "ン")
                .replace("ga", "ガ").replace("gi", "ギ").replace("gu", "グ").replace("ge", "ゲ").replace("go", "ゴ")
                .replace("za", "ザ").replace("ji", "ジ").replace("zu", "ズ").replace("ze", "ゼ").replace("zo", "ゾ")
                .replace("da", "ダ").replace("de", "デ").replace("do", "ド")
                .replace("ba", "バ").replace("bi", "ビ").replace("bu", "ブ").replace("be", "ベ").replace("bo", "ボ")
                .replace("pa", "パ").replace("pi", "ピ").replace("pu", "プ").replace("pe", "ペ").replace("po", "ポ")
                .replaceAll("aa", "アー").replaceAll("ii", "イー").replaceAll("uu", "ウー")
                .replaceAll("ee", "エー").replaceAll("oo", "オー");
    }

    private String spellAcronym(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) {
            b.append(letterNames.getOrDefault(String.valueOf(c), String.valueOf(c))).append("・");
        }
        if (b.length() > 0) b.setLength(b.length() - 1);
        return b.toString();
    }

    private enum Type { WORD, NUMBER, ACRONYM, OTHER }
    private record Token(Type type, String text) {}

    private List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<acronym>\\b(?!AM|PM)[A-Z]{2,5}\\b)|(?<num>\\b[0-9]+(?:\\.[0-9]+)?\\b)|(?<word>[A-Za-z'-]+)").matcher(s);
        int idx = 0;
        while (m.find()) {
            if (m.start() > idx) out.add(new Token(Type.OTHER, s.substring(idx, m.start())));
            if (m.group("acronym") != null) out.add(new Token(Type.ACRONYM, m.group()));
            else if (m.group("num") != null) out.add(new Token(Type.NUMBER, m.group()));
            else if (m.group("word") != null) out.add(new Token(Type.WORD, m.group()));
            idx = m.end();
        }
        if (idx < s.length()) out.add(new Token(Type.OTHER, s.substring(idx)));
        return out;
    }

    private static PhonoKana instance;
    public static PhonoKana getInstance() {
        if (instance == null) instance = createDefaultInstance();
        return instance;
    }

    private static PhonoKana createDefaultInstance() {
        Map<String, String> ex = new HashMap<>();
        try (InputStream is = PhonoKana.class.getResourceAsStream("/phonokana_exceptions.properties")) {
            if (is == null) {
                throw new RuntimeException("Could not find phonokana_exceptions.properties in resources.");
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            props.forEach((key, value) -> ex.put((String) key, (String) value));
            System.out.println("Ciel Debug: Loaded " + ex.size() + " internal phonetic exceptions.");
        } catch (Exception e) {
            System.err.println("Ciel FATAL: Failed to load internal phonetic exceptions. Pronunciation will be impacted.");
            e.printStackTrace();
        }
        Map<Pattern, String> rules = new LinkedHashMap<>();
        rules.put(Pattern.compile("\\bwh"), "hw");
        rules.put(Pattern.compile("(?<=[^aeiou])t\\b"), "to");
        rules.put(Pattern.compile("(?<=[^aeiou])d\\b"), "do");
        rules.put(Pattern.compile("(?<=[^aeiou])r\\b"), "ru");
        rules.put(Pattern.compile("([bcfghjkmpqsvwxz])\\b"), "$1u");
        rules.put(Pattern.compile("c(?=[eiy])"), "s");
        rules.put(Pattern.compile("c"), "k");
        rules.put(Pattern.compile("g(?=[eiy])"), "j");
        rules.put(Pattern.compile("([st])([r])"), "$1u$2");
        rules.put(Pattern.compile("([bcdfghkpt])([lr])"), "$1u$2");
        rules.put(Pattern.compile("tion\\b"), "shon");
        rules.put(Pattern.compile("sion\\b"), "jon");
        rules.put(Pattern.compile("([aeiou])ture\\b"), "$1chaa");
        rules.put(Pattern.compile("([aeiou])sure\\b"), "$1jaa");
        rules.put(Pattern.compile("\\bthe\\b"), "za");
        rules.put(Pattern.compile(" th"), "s");
        rules.put(Pattern.compile("ph"), "f");
        rules.put(Pattern.compile("igh"), "ai");
        rules.put(Pattern.compile("kn"), "n");
        rules.put(Pattern.compile("ou"), "au");
        rules.put(Pattern.compile("ow"), "au");
        rules.put(Pattern.compile("ar\\b"), "aa");
        rules.put(Pattern.compile("er\\b"), "aa");
        rules.put(Pattern.compile("or\\b"), "oo");
        rules.put(Pattern.compile("ee"), "ii");
        rules.put(Pattern.compile("ea"), "ii");
        rules.put(Pattern.compile("oa"), "oo");
        rules.put(Pattern.compile("oo"), "uu");
        rules.put(Pattern.compile("(?<=[^aeiou])y\\b"), "ii");
        Map<String, String> letters = Map.ofEntries(
                Map.entry("A", "エー"), Map.entry("B", "ビー"), Map.entry("C", "シー"),
                Map.entry("D", "ディー"), Map.entry("E", "イー"), Map.entry("F", "エフ"),
                Map.entry("G", "ジー"), Map.entry("H", "エイチ"), Map.entry("I", "アイ"),
                Map.entry("J", "ジェイ"), Map.entry("K", "ケー"), Map.entry("L", "エル"),
                Map.entry("M", "エム"), Map.entry("N", "エヌ"), Map.entry("O", "オー"),
                Map.entry("P", "ピー"), Map.entry("Q", "キュー"), Map.entry("R", "アール"),
                Map.entry("S", "エス"), Map.entry("T", "ティー"), Map.entry("U", "ユー"),
                Map.entry("V", "ブイ"), Map.entry("W", "ダブリュー"), Map.entry("X", "エックス"),
                Map.entry("Y", "ワイ"), Map.entry("Z", "ズィー")
        );
        return new PhonoKana(ex, rules, letters);
    }
}
