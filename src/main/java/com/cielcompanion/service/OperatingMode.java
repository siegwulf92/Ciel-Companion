package com.cielcompanion.service;

/**
 * Defines the different operational modes for Ciel.
 */
public enum OperatingMode {
    INTEGRATED, // "Normal" mode for idle chat and general assistance
    ATTENTIVE,  // Silent mode, only responds to wake word
    DND_ASSISTANT // D&D mode for dice rolls, rule lookups, etc.
}
