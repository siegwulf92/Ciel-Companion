package com.cielcompanion.memory;

/**
 * Represents a record of a line of dialogue that Ciel has spoken.
 * This is used to prevent her from repeating herself too often.
 *
 * @param lineKey A unique identifier for the line (e.g., "phase1.common.0").
 * @param lineText The full text of the line that was spoken.
 * @param spokenAtMs The timestamp when the line was spoken.
 * @param phase The idle phase (1, 2, or 3) in which the line was spoken.
 */
public record SpokenLine(
    String lineKey,
    String lineText,
    long spokenAtMs,
    int phase
) {}

