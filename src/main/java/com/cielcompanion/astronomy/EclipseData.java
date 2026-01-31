package com.cielcompanion.astronomy;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

public class EclipseData {

    /**
     * Represents a single eclipse event.
     */
    public record Event(LocalDate date, String type, String description, String visibility) {}

    private static final List<Event> ECLIPSES = List.of(
        new Event(LocalDate.of(2024, 3, 25), "Penumbral Lunar", "a penumbral lunar eclipse", "Americas"),
        new Event(LocalDate.of(2024, 4, 8), "Total Solar", "a total solar eclipse", "North America"),
        new Event(LocalDate.of(2024, 9, 18), "Partial Lunar", "a partial lunar eclipse", "Americas, Europe, Africa"),
        new Event(LocalDate.of(2024, 10, 2), "Annular Solar", "an annular solar eclipse", "Pacific, South America"),
        new Event(LocalDate.of(2025, 3, 14), "Total Lunar", "a total lunar eclipse", "Americas"),
        new Event(LocalDate.of(2025, 3, 29), "Partial Solar", "a partial solar eclipse", "Europe, North Africa"),
        new Event(LocalDate.of(2025, 9, 7), "Total Lunar", "a total lunar eclipse", "Europe, Asia, Africa, Australia"),
        new Event(LocalDate.of(2025, 9, 21), "Partial Solar", "a partial solar eclipse", "Antarctica, South Africa"),
        new Event(LocalDate.of(2026, 2, 17), "Annular Solar", "an annular solar eclipse", "Antarctica"),
        new Event(LocalDate.of(2026, 3, 3), "Total Lunar", "a total lunar eclipse", "Asia, Australia, Pacific, Americas"),
        new Event(LocalDate.of(2026, 8, 12), "Total Solar", "a total solar eclipse", "North America, Europe"),
        new Event(LocalDate.of(2026, 8, 28), "Partial Lunar", "a partial lunar eclipse", "Europe, Africa, Asia, Australia")
    );

    /**
     * Finds the next relevant eclipse within a given time window around a specific date.
     * @param today The current date to check against.
     * @param lookaheadDays How many days in the future to look.
     * @param lookbehindDays How many days in the past to look (for "aftermath" messages).
     * @return An Optional containing the closest event if one is found within the window.
     */
    public static Optional<Event> findNextEvent(LocalDate today, int lookaheadDays, int lookbehindDays) {
        return ECLIPSES.stream()
            .filter(event -> {
                long daysBetween = ChronoUnit.DAYS.between(today, event.date());
                return daysBetween >= -lookbehindDays && daysBetween <= lookaheadDays;
            })
            .min((e1, e2) -> {
                long d1 = Math.abs(ChronoUnit.DAYS.between(today, e1.date()));
                long d2 = Math.abs(ChronoUnit.DAYS.between(today, e2.date()));
                return Long.compare(d1, d2);
            });
    }
}

