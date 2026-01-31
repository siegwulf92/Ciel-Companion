package com.cielcompanion.astronomy;

import java.time.Month;
import java.util.Map;

public class Constellations {
    // REWORKED: All constellation names are now pre-converted to Katakana-English.
    private static final Map<Integer, String> SEASONAL = Map.ofEntries(
        Map.entry(1, "オリオン、トーラス、ジェミニ"),
        Map.entry(2, "オリオン、ケイニス メイジャー、アウリガ"),
        Map.entry(3, "レオ、キャンサー、ジェミニ"),
        Map.entry(4, "レオ、ヴァーゴ、アーサ メイジャー"),
        Map.entry(5, "ヴァーゴ、ブーオーティーズ、ライブラ"),
        Map.entry(6, "スコーピアス、サジタリウス、ハーキュリーズ"),
        Map.entry(7, "スコーピアス、サジタリウス、ライラ"),
        Map.entry(8, "サジタリウス、カプリコーナス、アクィラ"),
        Map.entry(9, "ペガサス、アクエリアス、カプリコーナス"),
        Map.entry(10, "ペガサス、アンドロメダ、パイシーズ"),
        Map.entry(11, "アンドロメダ、パーシアス、パイシーズ"),
        Map.entry(12, "オリオン、トーラス、パーシアス")
    );

    public static String forMonth(Month m) {
        return SEASONAL.getOrDefault(m.getValue(), "季節によって異なります");
    }
}
