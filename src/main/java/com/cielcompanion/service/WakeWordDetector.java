package com.cielcompanion.service;

import com.google.gson.JsonParser;
import org.vosk.Model;
import org.vosk.Recognizer;
import javax.sound.sampled.*;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WakeWordDetector {

    // This class is currently not used in the main application flow
    // but is kept for potential future development.

    private static final String WAKE_WORD = "hey ciel";
    private Model voskModel;
    private ExecutorService executor;
    private Future<?> detectionTask;

    public void initialize() throws IOException {
        // voskModel = new Model("model/vosk-model-small-en-us-0.15");
        // executor = Executors.newSingleThreadExecutor();
        // startDetection();
    }

    private void startDetection() {
        // Detection logic is disabled for now to prevent microphone conflicts.
    }

    public void close() {
        if (detectionTask != null) {
            detectionTask.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (voskModel != null) {
            voskModel.close();
        }
    }
}
