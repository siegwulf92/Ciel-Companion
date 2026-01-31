package com.cielcompanion.memory;

/**
 * REWORKED: Represents a single key-value fact in LTM, now with metadata.
 *
 * @param key The subject of the fact (e.g., "my opinion on snow").
 * @param value The information associated with the key (e.g., "I don't like it").
 * @param createdAtMs The timestamp when the fact was recorded.
 * @param tags Comma-separated tags for categorization (e.g., "preference,weather").
 * @param source The origin of the fact ("user", "system", "inferred").
 * @param version The version number of this fact, for future data migrations.
 */
public record Fact(
    String key,
    String value,
    long createdAtMs,
    String tags,
    String source,
    int version
) {}

