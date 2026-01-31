package com.cielcompanion.dnd;

/**
 * Represents a single lore note for the D&D mode.
 *
 * @param key The subject of the note (e.g., "gregor the blacksmith").
 * @param content The body of the note containing all the details.
 * @param createdAtMs Timestamp of creation.
 * @param updatedAtMs Timestamp of the last update.
 */
public record LoreNote(
    String key,
    String content,
    long createdAtMs,
    long updatedAtMs
) {}
