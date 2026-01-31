package com.cielcompanion.astronomy;

import com.cielcompanion.service.LineManager;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class VisiblePlanets {

    private static final Map<Month, List<String>> EVENING_PLANETS_ALMANAC = Map.ofEntries(
        Map.entry(Month.JANUARY, List.of("jupiter")),
        Map.entry(Month.FEBRUARY, List.of("jupiter")),
        Map.entry(Month.MARCH, List.of("venus")),
        Map.entry(Month.APRIL, List.of("venus", "mars")),
        Map.entry(Month.MAY, List.of("venus", "mars")),
        Map.entry(Month.JUNE, List.of("venus", "mars")),
        Map.entry(Month.JULY, List.of("venus", "saturn")),
        Map.entry(Month.AUGUST, List.of("saturn")),
        Map.entry(Month.SEPTEMBER, List.of("saturn", "neptune", "uranus")),
        Map.entry(Month.OCTOBER, List.of("saturn", "jupiter")),
        Map.entry(Month.NOVEMBER, List.of("saturn", "jupiter")),
        Map.entry(Month.DECEMBER, List.of("saturn", "jupiter"))
    );

    // REWORKED: This now returns the pre-converted Katakana-English lines directly.
    public static List<String> getVisiblePlanetLines(Month month) {
        List<String> visiblePlanetKeys = EVENING_PLANETS_ALMANAC.getOrDefault(month, List.of());

        if (visiblePlanetKeys.contains("jupiter") && visiblePlanetKeys.contains("saturn")) {
            return LineManager.getPlanetLine("jupitersaturn")
                .map(line -> List.of(line.text()))
                .orElse(List.of());
        } else {
            return visiblePlanetKeys.stream()
                .map(key -> LineManager.getPlanetLine(key).map(line -> line.text()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        }
    }
}
