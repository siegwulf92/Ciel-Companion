package com.cielcompanion.memory;

/**
 * Represents a single, timestamped event in Ciel's memory.
 * This is a Java Record, which is a modern, concise way to create a class that is a simple data carrier.
 *
 * @param id The unique ID from the database (auto-incremented).
 * @param tsMs The timestamp of the event in milliseconds since the epoch.
 * @param type A string identifying the event type (e.g., "ciel.speech.spoken", "user.speech.heard").
 * @param severity The severity level (e.g., "INFO", "WARN", "ERROR").
 * @param source The origin of the event (e.g., "Ciel", "User", "SystemSensor").
 * @param payloadJson A JSON string containing the event's data (e.g., {"text":"Hello"}).
 * @param hash An optional hash for deduplication.
 * @param processedAtMs A timestamp for when this event was processed by the memory compactor.
 */
public record Event(
    long id,
    long tsMs,
    String type,
    String severity,
    String source,
    String payloadJson,
    String hash,
    Long processedAtMs
) {}
