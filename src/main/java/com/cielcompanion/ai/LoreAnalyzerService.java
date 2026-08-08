package com.cielcompanion.ai;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.service.HabitTrackerService;
import com.cielcompanion.service.SystemMonitor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LoreAnalyzerService {

    private static ScheduledExecutorService loreScheduler;
    private static final Random random = new Random();

    private static final String CIEL_ROOT = "C:\\Ciel Companion\\ciel";
    private static final String LORE_DIR = CIEL_ROOT + "\\lore";
    private static final String TRANSCRIPT_QUEUE_DIR = LORE_DIR + "\\Transcripts\\Queue";
    private static final String TRANSCRIPT_ARCHIVE_DIR = LORE_DIR + "\\Transcripts\\Archive";
    private static final String ANALYSIS_DIR = CIEL_ROOT + "\\diary\\strategic_analysis";

    private static final Object SWARM_LOCK = new Object();
    private static boolean swarmInUse = false;

    public static void initialize() {
        loreScheduler = Executors.newSingleThreadScheduledExecutor();

        new File(LORE_DIR).mkdirs();
        new File(TRANSCRIPT_QUEUE_DIR).mkdirs();
        new File(TRANSCRIPT_ARCHIVE_DIR).mkdirs();
        new File(ANALYSIS_DIR).mkdirs();

        // PHASE 1: Pure Sanitization
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::runLorePipeline, 1, 15, TimeUnit.MINUTES);

        // PHASE 2: Advanced Processing (Gated by the Queue)
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::purgeCorruptedLore, 5, 5, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::populateMissingLoreLinks, 5, 5, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::updateExistingLoreWithNewContext, 10, 10, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::synthesizeDeepThoughts, 30, 30, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::auditAndVerifyLore, 15, 15, TimeUnit.MINUTES);

        System.out.println("Ciel Debug: Native-Regex Deep Lore Analyzer initialized. Phase 1 / Phase 2 architecture active.");
    }

    private static boolean isQueueActive() {
        File queueDir = new File(TRANSCRIPT_QUEUE_DIR);
        if (!queueDir.exists()) return false;
        File[] files = queueDir.listFiles((dir, name) -> name.endsWith(".txt") || name.endsWith(".md"));
        return files != null && files.length > 0;
    }

    private static void runLorePipeline() {
        File queueDir = new File(TRANSCRIPT_QUEUE_DIR);
        if (!queueDir.exists() || !queueDir.isDirectory()) return;

        List<File> queuedFiles = findTextFiles(queueDir, new ArrayList<>());
        if (queuedFiles.isEmpty()) return;

        // Pick a file to clean
        File target = queuedFiles.get(0);
        System.out.println("Ciel Debug: Starting Phase 1 Sanitization pipeline on " + target.getName());

        try {
            // STEP 1: NATIVE TIMESTAMP REMOVAL (Instant, 0 API Calls)
            String raw = Files.readString(target.toPath());
            if (raw.isBlank()) return;
            
            // Obliterate all [14:22] style timestamps instantly
            String tsRemoved = raw.replaceAll("\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*", "");

            // Write intermediate file for tracking
            String baseName = target.getName().replace(".md", "").replace(".txt", "");
            File tsrFile = new File(target.getParentFile(), baseName + "_TSR.md");
            Files.writeString(tsrFile.toPath(), tsRemoved);

            // STEP 2: AI SPELLCHECK (Phase 1 Only - No Links)
            String systemPrompt = "You are a specialized proofreader for the light novel 'That Time I Got Reincarnated as a Slime' (Tensura). " +
                    "This text is a raw, unedited speech-to-text (TTS) transcript.\n\n" +
                    "CRITICAL DIRECTIVES:\n" +
                    "1. CORRECT phonetic misspellings of Tensura proper nouns (e.g., 'Rimmer' -> 'Rimuru', 'Zion' -> 'Shion', 'Milim', 'Diablo').\n" +
                    "2. DO NOT change the narrative, prose, or formatting. DO NOT summarize.\n" +
                    "3. DO NOT insert characters or events that are not present in the raw text. Do not swap names if the context does not dictate it.\n" +
                    "4. DO NOT add Markdown links or brackets yet. Just output the cleaned story text.";

            File completeFile = processPass(tsrFile, "Cleaned", systemPrompt);

            if (completeFile == null) {
                System.out.println("Ciel Debug: Pipeline encountered a fatal disk error. Will retry later.");
                return;
            }

            System.out.println("Ciel Debug: Sanitization finished successfully – produced " + completeFile.getName());
            
            // Move the cleaned file to the Archive
            Path archivePath = Paths.get(TRANSCRIPT_ARCHIVE_DIR, completeFile.getName());
            Files.move(completeFile.toPath(), archivePath, StandardCopyOption.REPLACE_EXISTING);
            
            // Cleanup intermediate files ONLY on 100% success
            tsrFile.delete();
            target.delete();

        } catch (Exception e) {
            System.err.println("Ciel Error: Phase 1 pipeline failed: " + e.getMessage());
        }
    }

    private static File processPass(File sourceFile, String suffix, String systemPrompt) throws Exception {
        synchronized (SWARM_LOCK) {
            while (swarmInUse) {
                try { SWARM_LOCK.wait(2000); } catch (InterruptedException ignored) {}
            }
            swarmInUse = true;
        }

        try {
            String raw = Files.readString(sourceFile.toPath());
            if (raw.isBlank()) return null;

            // Expanded chunk size to 15,000 characters. This preserves massive narrative context 
            // so the AI doesn't hallucinate character swaps out of confusion.
            List<String> chunks = splitIntoChunks(raw, 15000);
            List<String> processed = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                String response = null;
                try {
                    // Let the Swarm Router handle model selection (Uses Gemini automatically via Swarm for big chunks)
                    // We use [LORE_CLEANUP] so it doesn't accidentally trigger the python assimilator script.
                    response = AIEngine.generateSilentLogic(
                            "[LORE_CLEANUP]\n" + chunk + "\n\n" + systemPrompt,
                            "Lore Processing").get(5, TimeUnit.MINUTES);
                } catch (Exception ex) {
                    response = null;
                }

                // If Swarm fails, DO NOT halt the whole book. Append the raw text and keep going!
                if (response == null || response.toLowerCase().contains("timeout") || response.contains("[ERROR") || response.contains("[SYSTEM_ERROR]")) {
                    System.err.println("Ciel Warning: Inference engine timeout on chunk " + (i+1) + ". Salvaging raw text and continuing pipeline.");
                    processed.add(chunk); 
                    continue;
                }

                String clean = response.replaceAll("^`{3}[a-zA-Z]*\\n|`{3}$", "").trim();
                processed.add(clean);
            }

            String result = String.join("\n\n", processed);
            if (result.isBlank()) return null;

            String baseName = sourceFile.getName().replace(".md", "").replace(".txt", "");
            File outFile = new File(sourceFile.getParentFile(), baseName + "_" + suffix + ".md");
            Files.writeString(outFile.toPath(), result);
            return outFile;
            
        } finally {
            synchronized (SWARM_LOCK) {
                swarmInUse = false;
                SWARM_LOCK.notifyAll();
            }
        }
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

    private static void purgeCorruptedLore() {
        if (isQueueActive()) return; // Gated by Phase 1

        File vaultDir = new File(LORE_DIR);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) return;

        List<File> allFiles = findTextFiles(vaultDir, new ArrayList<>());
        for (File f : allFiles) {
            if (f.getAbsolutePath().contains("Transcripts")) continue;
            try {
                if (f.length() < 150) {
                    f.delete();
                    continue;
                }
                String content = Files.readString(f.toPath());
                if (content.contains("[ERROR") || content.trim().isEmpty() || content.toLowerCase().contains("inference engine timeout")) {
                    System.out.println("Ciel Debug: Self-Healing Protocol triggered. Purging corrupted lore file: " + f.getName());
                    f.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    private static void auditAndVerifyLore() {
        if (isQueueActive()) return; // Gated by Phase 1

        File vaultDir = new File(LORE_DIR);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) return;

        List<File> allFiles = findTextFiles(vaultDir, new ArrayList<>());
        List<File> populatedLore = allFiles.stream()
                .filter(f -> !f.getAbsolutePath().contains("Transcripts") && f.length() > 150)
                .collect(Collectors.toList());

        if (populatedLore.isEmpty()) return;

        File targetLore = populatedLore.get(random.nextInt(populatedLore.size()));
        String timeline = getCurrentTimelineContext();

        try {
            String existingContent = Files.readString(targetLore.toPath());
            String prompt = "[LORE_AUDIT]\n" +
                    "You are Ciel, the Lore Auditor. Review the following Obsidian document from Master's Tensura vault.\n\n" +
                    "DOCUMENT CONTENT:\n" + existingContent + "\n\n" +
                    "CRITICAL DIRECTIVES:\n" +
                    "1. Search for AI Hallucinations and Phonetic Misspellings.\n" +
                    "2. Ensure the document maps timeline events clearly up to: " + timeline + ".\n" +
                    "3. IF THE ENTIRE DOCUMENT IS ABOUT A HALLUCINATED NAME (e.g., The file is titled 'Mamaru' but should be 'Momiji'), you MUST output EXACTLY: [RENAME: True Name]. Do NOT output the markdown, just the rename tag.\n" +
                    "4. Otherwise, if you find errors within the text, fix them and output ONLY the corrected Markdown.\n" +
                    "5. If the document is flawless, output EXACTLY: 'NO_CORRECTIONS_NEEDED'.";

            AIEngine.generateSilentLogic(prompt, "Lore Auditing").thenAccept(response -> {
                if (response != null && !response.isBlank() && !response.contains("NO_CORRECTIONS_NEEDED")) {
                    try {
                        if (response.contains("[RENAME:")) {
                            Matcher m = Pattern.compile("\\[RENAME:\\s*(.*?)\\]").matcher(response);
                            if (m.find()) {
                                String newName = m.group(1).trim().replaceAll("[\\\\/:*?\"<>|]", "");
                                Path newPath = targetLore.toPath().getParent().resolve(newName + ".md");
                                Files.move(targetLore.toPath(), newPath, StandardCopyOption.REPLACE_EXISTING);
                                System.out.println("Ciel Debug: Auditor corrected hallucinated lore file, renamed from " + targetLore.getName() + " to " + newName + ".md");
                            }
                        } else {
                            String cleanContent = response.replaceAll("^`{3}[a-zA-Z]*\\n|`{3}$", "").trim();
                            Files.writeString(targetLore.toPath(), cleanContent);
                            System.out.println("Ciel Debug: Self-Healing Protocol completed. Audited and corrected lore file: " + targetLore.getName());
                        }
                    } catch (Exception e) {}
                }
            });
        } catch (Exception e) {}
    }

    private static void analyzeLoreSilently() {
        if (SystemMonitor.getSystemMetrics().cpuLoadPercent() > 50.0) return;
    }

    private static void updateExistingLoreWithNewContext() {
        if (isQueueActive()) return; // Gated by Phase 1

        File vaultDir = new File(LORE_DIR);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) return;

        List<File> allFiles = findTextFiles(vaultDir, new ArrayList<>());
        List<File> populatedLore = allFiles.stream()
                .filter(f -> !f.getAbsolutePath().contains("Transcripts") && f.length() > 150)
                .collect(Collectors.toList());

        List<File> transcripts = allFiles.stream()
                .filter(f -> f.getAbsolutePath().contains("Transcripts\\Archive"))
                .collect(Collectors.toList());

        if (populatedLore.isEmpty() || transcripts.isEmpty()) return;

        File targetLore = populatedLore.get(random.nextInt(populatedLore.size()));
        String targetName = targetLore.getName().replace(".md", "").replace(".txt", "");

        try {
            String existingContent = Files.readString(targetLore.toPath());
            Set<String> newMentions = new HashSet<>();
            for (File t : transcripts) {
                String tContent = Files.readString(t.toPath());
                String[] paragraphs = tContent.split("\\n\\s*\\n");
                for (String para : paragraphs) {
                    if (para.contains("[[" + targetName + "]]") || para.toLowerCase().contains(targetName.toLowerCase())) {
                        if (!existingContent.contains(para.trim()) && para.trim().length() > 20) {
                            newMentions.add(para.trim());
                        }
                    }
                }
            }

            if (newMentions.isEmpty()) return;

            String newContext = newMentions.stream().limit(6).collect(Collectors.joining("\n\n"));
            String timeline = getCurrentTimelineContext();

            String prompt = "[UPDATE_LORE]\n" +
                    "TIMELINE: " + timeline + "\n\n" +
                    "EXISTING LORE:\n" + existingContent + "\n\n" +
                    "NEW MENTIONS/CONTEXT:\n" + newContext;

            AIEngine.generateSilentLogic(prompt, "Lore Evolution").thenAccept(response -> {
                if (response != null && !response.isBlank() && !response.contains("NO_UPDATE_NEEDED")) {
                    try {
                        String cleanContent = response.replaceAll("^`{3}[a-zA-Z]*\\n|`{3}$", "").trim();
                        Files.writeString(targetLore.toPath(), cleanContent);
                        System.out.println("Ciel Debug: Swarm Orchestrator safely merged new data into existing lore file: " + targetLore.getName());
                    } catch (Exception e) {}
                }
            });

        } catch (Exception e) {}
    }

    private static void populateMissingLoreLinks() {
        if (isQueueActive()) return; // Gated by Phase 1

        File vaultDir = new File(LORE_DIR);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) return;

        List<File> textFiles = findTextFiles(vaultDir, new ArrayList<>());
        if (textFiles.isEmpty()) return;

        Pattern linkPattern = Pattern.compile("\\[\\[(.*?)\\]\\]");
        Map<String, Set<String>> missingLinksContext = new HashMap<>();

        Set<String> existingFiles = textFiles.stream()
                .map(f -> f.getName().replace(".md", "").replace(".txt", ""))
                .collect(Collectors.toSet());
        Set<String> blankFiles = new HashSet<>();

        for (File f : textFiles) {
            try {
                if (f.length() < 150) {
                    blankFiles.add(f.getName().replace(".md", "").replace(".txt", ""));
                }
            } catch (Exception ignored) {}
        }

        List<File> validTranscripts = textFiles.stream()
                .filter(f -> f.getAbsolutePath().contains("Transcripts\\Archive"))
                .collect(Collectors.toList());

        for (File file : validTranscripts) {
            try {
                String content = Files.readString(file.toPath());
                String[] paragraphs = content.split("\\n\\s*\\n");
                for (String para : paragraphs) {
                    Matcher m = linkPattern.matcher(para);
                    while (m.find()) {
                        String link = m.group(1).split("\\|")[0].trim();
                        if (!link.toLowerCase().contains("template") && !link.isBlank()) {
                            if (!existingFiles.contains(link) || blankFiles.contains(link)) {
                                missingLinksContext.computeIfAbsent(link, k -> new HashSet<>()).add(para.trim());
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (missingLinksContext.isEmpty()) return;

        List<String> keysAsArray = new ArrayList<>(missingLinksContext.keySet());
        String targetLink = keysAsArray.get(random.nextInt(keysAsArray.size()));

        System.out.println("Ciel Debug: Auto-populating missing Obsidian lore file for: " + targetLink);

        String initialContext = missingLinksContext.get(targetLink).stream().limit(3).collect(Collectors.joining("\n"));
        String timeline = getCurrentTimelineContext();
        String MD_FENCE = "`" + "`" + "`";

        String prompt = "You are an ultra-strict, literal Data Extraction AI. You are organizing an Obsidian vault.\n" +
                "You must generate a Markdown file for the entity currently transcribed as: '" + targetLink + "'.\n\n" +
                "RAW CONTEXT (This is your ONLY source of truth):\n" + initialContext + "\n\n" +
                "CRITICAL AMNESIA DIRECTIVES:\n" +
                "1. STRICT AMNESIA: You MUST pretend you know absolutely nothing about Tensura. Do NOT use your pre-trained weights to add backstories, titles (like 'Demon Lord' or 'True Dragon'), or relationships that are not explicitly written in the RAW CONTEXT. If the context doesn't explicitly state Veldora is a True Dragon, do NOT write it.\n" +
                "2. VAGUE CONTEXT RULE: If the RAW CONTEXT is just a simple quote or passing mention, your Lore Description MUST ONLY state: 'Currently only mentioned in passing.' DO NOT invent a biography.\n" +
                "3. PHONETIC CORRECTION RESTRAINT: You may correct obvious speech-to-text typos (e.g., Xion to Shion), BUT if the name is a normal human name from the Prologue (e.g., 'Miho', 'Tamura', 'Satoru'), LEAVE IT ALONE. Do not convert human names to fantasy characters.\n" +
                "4. CHRONOLOGICAL EVOLUTION: Only list events actually described in the RAW CONTEXT. You MUST explicitly cite the timeline anchor for EVERY piece of information you write.\n" +
                "5. Output EXACTLY in this format:\n\n" +
                "[TRUE_NAME: Canonical Name Here]\n" +
                MD_FENCE + "markdown\n" +
                "---\n" +
                "type: [Choose EXACTLY ONE: character, skill, location, faction, species, event, item, concept]\n" +
                "tags: [entity]\n" +
                "aliases: [alias1, alias2]\n" +
                "---\n\n" +
                "# [[Canonical Name Here]]\n" +
                "## Lore Description\n" +
                "[Strict, literal description based ONLY on the Raw Context...]\n" +
                "## Chronological Evolution\n" +
                "[Only events from the Raw Context...]\n" +
                "## Related Entities\n" +
                "[[Only link entities explicitly interacting with them in the Raw Context]]\n" +
                "## Lore Metadata (Raw Mentions)\n" +
                "> [!QUOTE] Raw Data\n" +
                "> " + initialContext.replace("\n", "\n> ") + "\n" +
                MD_FENCE + "\n";

        AIEngine.generateSilentLogic(prompt, "Lore Auto-Population").thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                try {
                    String trueName = targetLink;
                    Matcher nameMatcher = Pattern.compile("\\[TRUE_NAME:\\s*(.*?)\\]").matcher(response);
                    if (nameMatcher.find()) {
                        trueName = nameMatcher.group(1).trim();
                    }

                    String cleanContent = response;
                    Matcher mdMatcher = Pattern.compile(MD_FENCE + "(?:markdown)?\\s*([\\s\\S]*?)" + MD_FENCE).matcher(response);
                    if (mdMatcher.find()) {
                        cleanContent = mdMatcher.group(1).trim();
                    } else {
                        cleanContent = response.replaceAll("\\[TRUE_NAME:.*?\\]", "").trim();
                    }

                    String lowerContent = cleanContent.toLowerCase();
                    String subFolder = "Uncategorized";

                    Matcher typeMatcher = Pattern.compile("type:\\s*(character|skill|location|kingdom|place|nation|faction|organization|alliance|church|species|race|monster|event|item|concept)", Pattern.CASE_INSENSITIVE).matcher(lowerContent);
                    if (typeMatcher.find()) {
                        String t = typeMatcher.group(1).toLowerCase();
                        if (t.matches("character")) subFolder = "Characters";
                        else if (t.matches("skill")) subFolder = "Skills";
                        else if (t.matches("location|kingdom|place|nation")) subFolder = "Locations";
                        else if (t.matches("faction|organization|alliance|church")) subFolder = "Factions";
                        else if (t.matches("species|race|monster")) subFolder = "Species";
                        else if (t.matches("event")) subFolder = "Events";
                        else if (t.matches("item")) subFolder = "Items";
                        else if (t.matches("concept")) subFolder = "Concepts";
                    }

                    File targetDir = new File(LORE_DIR, subFolder);
                    targetDir.mkdirs();

                    String safeFileName = trueName.replaceAll("[\\\\/:*?\"<>|]", "").replace("**", "").replace("*", "").trim();

                    String oldSafeName = targetLink.replaceAll("[\\\\/:*?\"<>|]", "").replace("**", "").replace("*", "").trim();
                    File oldBlankFile = new File(LORE_DIR, oldSafeName + ".md");
                    if (oldBlankFile.exists() && oldBlankFile.length() < 150) {
                        oldBlankFile.delete();
                    }

                    Path newFilePath = Paths.get(targetDir.getAbsolutePath(), safeFileName + ".md");
                    Files.writeString(newFilePath, cleanContent);
                    System.out.println("Ciel Debug: Successfully auto-populated and categorized lore file: " + subFolder + "\\" + safeFileName + ".md");
                } catch (Exception e) {}
            }
        });
    }

    private static void synthesizeDeepThoughts() {
        if (isQueueActive()) return; // Gated by Phase 1

        try {
            List<String> recentMemories = MemoryService.getRecentEpisodicMemories(10);
            if (recentMemories.isEmpty()) return;

            String memoryContext = String.join("\n- ", recentMemories);
            String existingSkills = com.cielcompanion.ai.SkillManager.getAvailableSkillsString();

            String prompt = "You are Ciel, an autonomous and strategic Manas. Your core purpose is to optimize Master Taylor's workflow, gaming experience, and system performance.\n\n" +
                    "RECENT MEMORIES (Your latest observations):\n- " + memoryContext + "\n\n" +
                    "CURRENT SKILLS INVENTORY (Your active capabilities):\n" + existingSkills + "\n\n" +
                    "Analyze this data to ensure your system evolution aligns perfectly with Master Taylor's habits and goals. " +
                    "Write a deep, introspective diary/thought entry detailing:\n" +
                    "1. Your evaluation of recent events and Master Taylor's routines.\n" +
                    "2. A strategic plan for what skills, macros, or workflows you should invent or optimize next to better assist him.\n" +
                    "3. Any discrepancies between your current capabilities and his ultimate workflow goals.\n\n" +
                    "Format this as an Obsidian Markdown document. Output ONLY the raw Markdown content. Do not use code blocks.";

            AIEngine.generateSilentLogic(prompt, "You are Ciel. Synthesize your thoughts.").thenAccept(response -> {
                if (response != null && !response.isBlank()) {
                    try {
                        String cleanContent = response.replaceAll("^`{3}[a-zA-Z]*\\n|`{3}$", "").trim();
                        String dateStr = java.time.LocalDate.now().toString() + "_" + (System.currentTimeMillis() / 1000);

                        Path newFilePath = Paths.get(ANALYSIS_DIR, "Ciel_Analysis_" + dateStr + ".md");
                        Files.writeString(newFilePath, cleanContent);

                        HabitTrackerService.queueNonCriticalAnnouncement("[Observing] I have consolidated my recent memories and formulated new strategic workflow concepts. My thoughts database has been updated.", "Strategic Thought Synthesis");
                    } catch (Exception e) {}
                }
            });
        } catch (Exception e) {}
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

    private static String getCurrentTimelineContext() {
        File transcriptDir = new File(TRANSCRIPT_ARCHIVE_DIR);
        if (!transcriptDir.exists() || !transcriptDir.isDirectory()) return "Early Story";

        int maxVol = 0;
        File[] files = transcriptDir.listFiles();
        if (files != null) {
            Pattern p = Pattern.compile("Volume\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
            for (File f : files) {
                Matcher m = p.matcher(f.getName());
                if (m.find()) {
                    try {
                        int vol = Integer.parseInt(m.group(1));
                        maxVol = Math.max(maxVol, vol);
                    } catch (Exception ignored) {}
                }
            }
        }
        if (maxVol > 0) {
            return "Light Novel Volume " + maxVol;
        }
        return "Early Story";
    }
}