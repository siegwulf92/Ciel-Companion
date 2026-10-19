package com.cielcompanion.ai;

import com.cielcompanion.util.ProjectPaths;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class LoreAnalyzerService {
    private static ScheduledExecutorService loreScheduler;

    private static final String CIEL_ROOT = ProjectPaths.ciel().toString();
    private static final String LORE_DIR = ProjectPaths.ciel().resolve("lore").toString();
    private static final String RAW_DIR = ProjectPaths.ciel().resolve("lore").resolve("Raw_Transcripts").toString();
    private static final String CLEAN_DIR = ProjectPaths.ciel().resolve("lore").resolve("Cleaned_Transcripts").toString();
    private static final String ARCHIVE_DIR = ProjectPaths.ciel().resolve("lore").resolve("Archived_Raw").toString();

    private static final Object PIPELINE_LOCK = new Object();
    private static boolean pipelineInUse = false;

    public static void initialize() {
        loreScheduler = Executors.newSingleThreadScheduledExecutor();

        new File(RAW_DIR).mkdirs();
        new File(CLEAN_DIR).mkdirs();
        new File(ARCHIVE_DIR).mkdirs();

        // Run the transcript cleaner every 5 minutes
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::processRawTranscripts, 1, 5, TimeUnit.MINUTES);

        System.out.println("Ciel Debug: Simplified Lore Analyzer (Transcript Cleaner) initialized.");
    }

    private static void processRawTranscripts() {
        File rawDir = new File(RAW_DIR);
        if (!rawDir.exists() || !rawDir.isDirectory()) return;

        List<File> mdFiles = findTextFiles(rawDir, new ArrayList<>());
        if (mdFiles.isEmpty()) return;

        File target = mdFiles.get(0); // Pick the first available file in the queue
        System.out.println("Ciel Debug: Starting transcript cleaning pipeline on " + target.getName());

        synchronized (PIPELINE_LOCK) {
            if (pipelineInUse) return;
            pipelineInUse = true;
        }

        try {
            String rawText = Files.readString(target.toPath());
            if (rawText.isBlank()) {
                target.delete(); // Safe to delete completely empty files
                return;
            }

            // 1. Native Regex Timestamp Removal (0 API Calls)
            String tsRemoved = rawText.replaceAll("\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*", "");

            // 2. Split into massive 15,000 char chunks to utilize 1M+ context cloud models
            // Smaller chunks keep proofread and audit requests within practical
            // provider limits and make failures attributable to a single part.
            List<String> chunks = splitIntoChunks(tsRemoved, 3500);
            List<String> cleanedChunks = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                System.out.println("Ciel Debug: Processing chunk " + (i+1) + "/" + chunks.size() + " of " + target.getName());

                // ---------------------------------------------------------
                // PASS 1: CLEANING (Temp 0.3)
                // ---------------------------------------------------------
                String cleanPrompt = "[raw_transcript_spellcheck]\n" +
                        "You are an expert editor reviewing a raw, speech-to-text transcript of the light novel 'That Time I Got Reincarnated as a Slime' (Tensura).\n" +
                        "RAW CHUNK:\n" + chunk;
                        
                String cleanSystem = "CRITICAL RULES:\n" +
                        "1. Fix phonetically misspelled Tensura names and terms (e.g., Xion -> Shion, Rimuru, Veldora, Tempest, Jura).\n" +
                        "2. Fix standard punctuation and grammatical errors caused by speech-to-text software.\n" +
                        "3. DO NOT change the narrative prose, rewrite the story, or summarize. Keep the exact flow and wording of the book.\n" +
                        "4. If unsure about a word, leave it as is.\n" +
                        "5. Output ONLY the cleaned story prose. No markdown fences, no conversational text.";

                // Pass blank model to let openjarvis.py dynamic router pick the best High-Context Cloud model
                String cleanedText = runLorePass(cleanPrompt, cleanSystem, target, i, "proofread", 0.3);

                if (isBadResponse(cleanedText)) {
                    throw new Exception("Swarm Editor failed or timed out on chunk " + (i+1));
                }

                // ---------------------------------------------------------
                // PASS 2: AUDIT (Temp 0.1)
                // ---------------------------------------------------------
                String auditPrompt = "[raw_transcript_audit]\n" +
                        "You are a strict QA Auditor. Compare the ORIGINAL raw transcript to the CLEANED version of 'That Time I Got Reincarnated as a Slime'.\n" +
                        "ORIGINAL:\n" + chunk + "\n\n" +
                        "CLEANED:\n" + cleanedText;
                        
                String auditSystem = "CRITICAL RULES:\n" +
                        "1. Ensure no prose, details, or events were deleted or rewritten in the CLEANED text. It must be identical to ORIGINAL aside from typo/name fixes.\n" +
                        "2. If the CLEANED text contains AI hallucinations, bullet points, or changed details, FIX IT by restoring the ORIGINAL prose (keeping only corrected names).\n" +
                        "3. If the CLEANED text is accurate to the ORIGINAL prose, output the CLEANED text exactly as is.\n" +
                        "4. Output ONLY the verified story prose. No markdown fences, no conversational text.";

                String auditedText = runLorePass(auditPrompt, auditSystem, target, i, "audit", 0.1);

                if (isBadResponse(auditedText)) {
                    throw new Exception("Swarm Auditor failed or timed out on chunk " + (i+1));
                }

                // Clean up any stray markdown blocks the AI might have tried to insert
                String finalChunk = auditedText.replaceAll("^`{3}[a-zA-Z]*\\n|`{3}$", "").trim();
                cleanedChunks.add(finalChunk);
            }

            // 3. Assemble and Save
            String finalOutput = String.join("\n\n", cleanedChunks);
            if (finalOutput.isBlank()) throw new Exception("Final concatenated output was blank.");

            File outFile = new File(CLEAN_DIR, target.getName());
            File tempFile = new File(CLEAN_DIR, target.getName() + ".tmp");
            Files.writeString(tempFile.toPath(), finalOutput);
            try {
                Files.move(tempFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            System.out.println("Ciel Debug: Transcript cleaning successful for " + target.getName() + ". Saved to Cleaned_Transcripts.");

            // Archive the raw file safely instead of deleting it
            Files.move(target.toPath(), new File(ARCHIVE_DIR, target.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);

        } catch (Exception e) {
            System.err.println("Ciel Error: Transcript pipeline failed for " + target.getName() + ": " + e.getMessage());
        } finally {
            synchronized (PIPELINE_LOCK) {
                pipelineInUse = false;
            }
        }
    }

    private static String runLorePass(String prompt, String system, File source, int chunkIndex,
                                     String pass, double temperature) throws Exception {
        long started = System.nanoTime();
        java.util.concurrent.CompletableFuture<String> request = AIEngine.generateSilentLogicWithModel(
                prompt, system, null, temperature, "Lore Processing");
        try {
            String result = request.get(4, TimeUnit.MINUTES);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.println("Ciel Transcript Pipeline: file=" + source.getName() + " chunk=" + (chunkIndex + 1)
                    + " pass=" + pass + " result=" + ((result == null || result.isBlank()) ? "empty" : "ok")
                    + " chars=" + (result == null ? 0 : result.length()) + " elapsedMs=" + elapsedMs);
            return result;
        } catch (TimeoutException e) {
            request.cancel(true);
            System.err.println("Ciel Transcript Pipeline: file=" + source.getName() + " chunk=" + (chunkIndex + 1)
                    + " pass=" + pass + " result=timeout limitSeconds=240");
            throw e;
        } catch (Exception e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.err.println("Ciel Transcript Pipeline: file=" + source.getName() + " chunk=" + (chunkIndex + 1)
                    + " pass=" + pass + " result=exception type=" + e.getClass().getSimpleName()
                    + " elapsedMs=" + elapsedMs);
            throw e;
        }
    }

    private static boolean isBadResponse(String response) {
        if (response == null || response.isBlank()) return true;
        String lower = response.toLowerCase();
        return lower.contains("timeout") || lower.contains("[error") || lower.contains("[system_error]");
    }

    private static List<String> splitIntoChunks(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLen, text.length());
            if (end < text.length()) {
                int lastSpace = Math.max(text.lastIndexOf(' ', end), text.lastIndexOf('\n', end));
                if (lastSpace > start) end = lastSpace;
            }
            chunks.add(text.substring(start, end).trim());
            start = end;
            while (start < text.length() && (text.charAt(start) == ' ' || text.charAt(start) == '\n' || text.charAt(start) == '\r'))
                start++;
        }
        return chunks;
    }

    private static List<File> findTextFiles(File directory, List<File> files) {
        File[] fList = directory.listFiles();
        if (fList != null) {
            for (File file : fList) {
                if (file.isFile() && (file.getName().endsWith(".txt") || file.getName().endsWith(".md"))) {
                    files.add(file);
                } else if (file.isDirectory()) {
                    findTextFiles(file, files);
                }
            }
        }
        return files;
    }
}
