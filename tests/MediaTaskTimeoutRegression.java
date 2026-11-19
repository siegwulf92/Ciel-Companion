package com.cielcompanion.ai;

public class MediaTaskTimeoutRegression {
    public static void main(String[] args) {
        for (String intent : new String[]{"Media Persona Commentary", "Media Persona Commentary Prepare",
                "Paused Media Recognition"}) {
            if (!AIEngine.isTimeSensitiveMediaTask(intent))
                throw new AssertionError("Media stage escaped its bounded deadline: " + intent);
        }
        if (AIEngine.isTimeSensitiveMediaTask(null) || AIEngine.isTimeSensitiveMediaTask("Financial Data Analysis"))
            throw new AssertionError("Unrelated tasks must retain their own deadline");
        System.out.println("Media task deadline regression passed.");
        System.exit(0); // AIEngine owns long-lived background executors.
    }
}
