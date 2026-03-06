package com.cielcompanion.ai;

import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * A dedicated engine to forcefully translate English dates, times, and numbers 
 * into accurate Katakana to protect the TTS engine's immersion.
 */
public class PhonoKanaSanitizer {

    public static String numberToKatakana(int num) {
        if (num == 0) return "ゼロ";
        
        String[] ones = {"", "ワン", "トゥー", "スリー", "フォー", "ファイブ", "シックス", "セブン", "エイト", "ナイン"};
        String[] teens = {"テン", "イレブン", "トゥエルブ", "サーティーン", "フォーティーン", "フィフティーン", "シックスティーン", "セブンティーン", "エイティーン", "ナインティーン"};
        String[] tens = {"", "", "トゥエンティ", "サーティ", "フォーティ", "フィフティ", "シックスティ", "セブンティ", "エイティ", "ナインティ"};
        
        if (num < 10) return ones[num];
        if (num < 20) return teens[num - 10];
        if (num < 100) return tens[num / 10] + (num % 10 != 0 ? " " + ones[num % 10] : "");
        if (num < 1000) return ones[num / 100] + " ハンドレッド" + (num % 100 != 0 ? " アンド " + numberToKatakana(num % 100) : "");
        
        return String.valueOf(num); // Fallback
    }

    public static String getCurrentTimeKatakana() {
        LocalDateTime now = LocalDateTime.now();
        int h = now.getHour();
        int m = now.getMinute();
        String amPm = h >= 12 ? "ピーエム" : "エーエム";
        
        int displayHour = h % 12;
        if (displayHour == 0) displayHour = 12;
        
        String hourStr = numberToKatakana(displayHour);
        String minStr = m == 0 ? "オクロック" : (m < 10 ? "オー " + numberToKatakana(m) : numberToKatakana(m));
        
        return hourStr + " " + minStr + " " + amPm;
    }

    public static String getCurrentDateKatakana() {
        LocalDateTime now = LocalDateTime.now();
        String dayOfWeek = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase();
        String month = now.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase();
        
        // Translate English Days to Katakana
        String dayKana = dayOfWeek
                .replace("MONDAY", "マンデイ").replace("TUESDAY", "チューズデイ").replace("WEDNESDAY", "ウェンズデイ")
                .replace("THURSDAY", "サーズデイ").replace("FRIDAY", "フライデイ").replace("SATURDAY", "サタデイ").replace("SUNDAY", "サンデイ");
                         
        // Translate English Months to Katakana
        String monthKana = month
                .replace("JANUARY", "ジャニュアリー").replace("FEBRUARY", "フェブラリー").replace("MARCH", "マーチ")
                .replace("APRIL", "エイプリル").replace("MAY", "メイ").replace("JUNE", "ジューン")
                .replace("JULY", "ジュライ").replace("AUGUST", "オーガスト").replace("SEPTEMBER", "セプテンバー")
                .replace("OCTOBER", "オクトーバー").replace("NOVEMBER", "ノーヴェンバー").replace("DECEMBER", "ディセンバー");

        String yearKana = "トゥー サウザンド トゥエンティ " + (now.getYear() == 2025 ? "ファイブ" : "シックス");
        String dayNumKana = numberToKatakana(now.getDayOfMonth());

        return dayKana + ", " + monthKana + " " + dayNumKana + ", " + yearKana;
    }

    // Protects the AI Context window by translating raw numbers into Katakana words
    public static String sanitizeSystemDataForAI(String rawData) {
        return rawData
                .replace("%", " パーセント ")
                .replace("CPU", "シー ピー ユー")
                .replace("RAM", "ラム");
    }
}