package com.cielcompanion.service.nlu;

/**
 * All possible user intents the NLU can recognize.
 */
public enum Intent {
    // ... [Previous Intents] ...
    GET_TIME,
    GET_DAILY_REPORT,
    GET_WEATHER,
    GET_WEATHER_FORECAST,
    SEARCH_WEB,
    FIND_APP_PATH,
    SCAN_FOR_APPS,
    GET_SYSTEM_STATUS,
    GET_TOP_MEMORY_PROCESS,
    GET_TOP_CPU_PROCESS,
    TERMINATE_PROCESS,
    TERMINATE_PROCESS_FORCE,
    INITIATE_SHUTDOWN,
    INITIATE_REBOOT,
    CANCEL_SHUTDOWN,
    REMEMBER_FACT,
    REMEMBER_FACT_SIMPLE,
    RECALL_FACT,
    OPEN_APPLICATION,
    START_ROUTINE,
    SET_MODE_ATTENTIVE,
    SET_MODE_DND,
    SET_MODE_INTEGRATED,
    TOGGLE_LISTENING,
    LEARN_PHONETIC,

    // D&D Specific Intents
    DND_ROLL_DICE,
    DND_PLAY_SOUND,
    DND_CREATE_SESSION_NOTE,
    DND_ADD_TO_SESSION_NOTE,
    DND_RECALL_SESSION_NOTE,
    DND_LINK_SESSION_NOTE,
    DND_RECALL_SESSION_LINKS,
    DND_REVEAL_LORE,
    DND_ANALYZE_LORE,
    DND_GET_RULE,
    DND_API_SEARCH,
    DND_RUN_AUDIT,
    DND_RECORD_MASTERY,
    DND_REPORT_SURGE,
    OPEN_CHEAT_SHEET,

    // NEW: Tensura Puzzle Intents
    TENSURA_ENTER_WORLD,      // Triggers the language lock
    TENSURA_CONFIRM_COPY,     // The solution: "Copy skill", "Yes do it", etc.

    // NEW On-Demand Astronomy Intents
    GET_MOON_PHASE,
    GET_VISIBLE_PLANETS,
    GET_CONSTELLATIONS,
    GET_ECLIPSES,

    EASTER_EGG,
    UNKNOWN
}