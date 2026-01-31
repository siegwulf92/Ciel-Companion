package com.cielcompanion.memory.stwm;

/**
 * A static service to manage Ciel's volatile, short-term working memory (STWM).
 * This acts as a single source of truth for all session-specific state.
 */
public class ShortTermMemoryService {

    private static ShortTermMemory memory;

    public ShortTermMemoryService() {
        initialize();
    }

    /**
     * Initializes the short-term memory instance.
     */
    public static void initialize() {
        if (memory == null) {
            memory = new ShortTermMemory();
            System.out.println("Ciel Debug: Short-Term Memory Service initialized.");
        }
    }

    /**
     * Provides access to the single instance of the short-term memory.
     * @return The ShortTermMemory object.
     */
    public static ShortTermMemory getMemory() {
        if (memory == null) {
            initialize();
        }
        return memory;
    }
}
