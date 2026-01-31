package com.cielcompanion.service.nlu;

import java.util.Map;

/**
 * A data record to hold the result of the IntentService's analysis.
 *
 * @param intent The identified intent (e.g., REMEMBER_FACT).
 * @param entities A map of extracted keywords (e.g., {key="my car", value="red"}).
 */
public record CommandAnalysis(Intent intent, Map<String, String> entities) {}

