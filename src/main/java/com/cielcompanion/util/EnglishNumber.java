package com.cielcompanion.util;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public class EnglishNumber {
    private static final Map<Integer, String> numberMap = Map.ofEntries(
        Map.entry(0, "ゼロ"), Map.entry(1, "ワン"), Map.entry(2, "ツー"), Map.entry(3, "スリー"), 
        Map.entry(4, "フォー"), Map.entry(5, "ファイブ"), Map.entry(6, "シックス"), Map.entry(7, "セブン"), 
        Map.entry(8, "エイト"), Map.entry(9, "ナイン"), Map.entry(10, "テン"),
        Map.entry(11, "イレブン"), Map.entry(12, "トゥエルブ"), Map.entry(13, "サーティーン"),
        Map.entry(14, "フォーティーン"), Map.entry(15, "フィフティーン"), Map.entry(16, "シックスティーン"),
        Map.entry(17, "セブンティーン"), Map.entry(18, "エイティーン"), Map.entry(19, "ナインティーン"),
        Map.entry(20, "トゥエンティ"), Map.entry(30, "サーティ"), Map.entry(40, "フォーティ"),
        Map.entry(50, "フィフティ"), Map.entry(60, "シクスティ"), Map.entry(70, "セブンティ"),
        Map.entry(80, "エイティ"), Map.entry(90, "ナインティ")
    );

    private static final String HUNDRED = "ハンドレッド";
    private static final String THOUSAND = "サウザンド";
    private static final String MILLION = "ミリオン";
    private static final String BILLION = "ビリオン";
    private static final String TRILLION = "トリリオン";
    private static final String OCLOCK = "オクロック";

    private static final List<String> MAGNITUDES = List.of("", THOUSAND, MILLION, BILLION, TRILLION);

    public static String convert(String numberStr) {
        try {
            long number = Long.parseLong(numberStr.replace(",", ""));
            if (number == 0) {
                return numberMap.get(0);
            }
            return convertLargeNumber(number);
        } catch (NumberFormatException e) {
            return convertDigitByDigit(numberStr);
        }
    }

    private static String convertLargeNumber(long number) {
        if (number < 0) {
            return "マイナス " + convertLargeNumber(-number);
        }

        if (number < 100) {
            return convertNumberUnder100((int) number);
        }

        List<String> parts = new ArrayList<>();
        int magnitudeIndex = 0;

        while (number > 0) {
            if (number % 1000 != 0) {
                String chunkWords = convertNumberUnder1000((int) (number % 1000));
                if (magnitudeIndex > 0) {
                    chunkWords += " " + MAGNITUDES.get(magnitudeIndex);
                }
                parts.add(0, chunkWords);
            }
            number /= 1000;
            magnitudeIndex++;
        }

        return String.join(" ", parts);
    }
    
    private static String convertNumberUnder1000(int number) {
        if (number >= 100) {
            String hundredPart = numberMap.get(number / 100) + " " + HUNDRED;
            int remainder = number % 100;
            if (remainder > 0) {
                return hundredPart + " " + convertNumberUnder100(remainder);
            }
            return hundredPart;
        } else {
            return convertNumberUnder100(number);
        }
    }

    private static String convertNumberUnder100(int number) {
        if (numberMap.containsKey(number)) {
            return numberMap.get(number);
        } else {
            int tens = (number / 10) * 10;
            int ones = number % 10;
            return numberMap.get(tens) + " " + numberMap.get(ones);
        }
    }

    private static String convertDigitByDigit(String number) {
        StringBuilder result = new StringBuilder();
        for (char c : number.toCharArray()) {
            if (Character.isDigit(c)) {
                result.append(numberMap.getOrDefault(Character.getNumericValue(c), "")).append(" ");
            }
        }
        return result.toString().trim();
    }

    public static String convertTimeToWords(String time) {
        String[] parts = time.split("[: ]");
        
        StringBuilder spokenTime = new StringBuilder();
        spokenTime.append(convert(parts[0]));

        if (parts[1].equals("00")) {
            spokenTime.append(" ").append(OCLOCK);
        } else if (parts[1].startsWith("0")) {
            spokenTime.append(" オー ").append(convert(parts[1].substring(1)));
        } else {
            spokenTime.append(" ").append(convert(parts[1]));
        }

        spokenTime.append(" ").append(parts[2].replace("AM", "エイエム").replace("PM", "ピーエム"));

        return spokenTime.toString();
    }
}
