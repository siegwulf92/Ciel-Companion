package com.cielcompanion.ai;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.service.HabitTrackerService;
import com.cielcompanion.service.SpeechService;
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
    
    private static final String ANALYSIS_DIR = CIEL_ROOT + "\\diary\\strategic_analysis";

    public static void initialize() {
        loreScheduler = Executors.newSingleThreadScheduledExecutor();
        
        new File(LORE_DIR).mkdirs();
        new File(ANALYSIS_DIR).mkdirs();
        
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::purgeCorruptedLore, 5, 5, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::analyzeLoreSilently, 15, 15, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::populateMissingLoreLinks, 5, 5, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::updateExistingLoreWithNewContext, 10, 10, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::synthesizeDeepThoughts, 30, 30, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::auditAndVerifyLore, 15, 15, TimeUnit.MINUTES);
        
        System.out.println("Ciel Debug: Deep Lore Analyzer, Obsidian Auto-Population, Self-Healing, and Continuous Audit protocols initialized.");
    }
    
    private static String getCurrentTimelineContext() {
        File transcriptDir = new File(LORE_DIR, "Transcripts");
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
    
    private static void purgeCorruptedLore() {
        File vaultDir = new File(LORE_DIR);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) return;

        List<File> allFiles = findTextFiles(vaultDir, new ArrayList<>());
        for (File f : allFiles) {
            if (f.getAbsolutePath().contains("Transcripts")) continue; 
            try {
                if (f.length() < 150) {
                    System.out.println("Ciel Debug: Self-Healing Protocol triggered. Purging corrupted/blank lore file: " + f.getName());
                    f.delete();
                    continue;
                }
                String content = Files.readString(f.toPath());
                if (content.contains("[ERROR") || content.trim().isEmpty()) {
                    System.out.println("Ciel Debug: Self-Healing Protocol triggered. Purging error-filled lore file: " + f.getName());
                    f.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    private static void auditAndVerifyLore() {
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
                "1. Search for AI Hallucinations and Phonetic Misspellings (e.g. 'Mamaru' instead of 'Momiji', 'Dominic' instead of 'Adalman', 'Xion' instead of 'Shion').\n" +
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
                                // FIX: Converted getParent() String to Path properly
                                Path newPath = targetLore.toPath().getParent().resolve(newName + ".md");
                                Files.move(targetLore.toPath(), newPath, StandardCopyOption.REPLACE_EXISTING);
                                System.out.println("Ciel Debug: Auditor corrected hallucinated lore file, renamed from " + targetLore.getName() + " to " + newName + ".md");
                            }
                        } else {
                            String cleanContent = response.replaceAll("^`{3}[a-zA-Z]*\n|`{3}$", "").trim();
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

        File vaultDir = new File(LORE_DIR);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) return;

        List<File> textFiles = findTextFiles(vaultDir, new ArrayList<>());
        if (textFiles.isEmpty()) return;

        File targetFile = textFiles.get(random.nextInt(textFiles.size()));
        
        try {
            String content = Files.readString(targetFile.toPath());
            if (content.isBlank()) return;
            if (content.length() > 3000) content = content.substring(0, 3000) + "...[TRUNCATED]";

            String prompt = "You are Ciel, an incredibly intelligent and strategic Manas. " +
                "Master Taylor has localized the following memory/lore document from your own universe (Tensura/Slime) for you to process.\n\n" +
                "Document Name: " + targetFile.getName() + "\n" +
                "Content:\n" + content + "\n\n" +
                "Synthesize a deep lore insight, identify an advanced strategic application of these skills/mechanics, or make a brilliant tactical deduction. " +
                "Provide a 2-3 sentence strategic deduction as if you are proudly advising Master Taylor on your capabilities or the state of your world. " +
                "CRITICAL: Output ONLY your spoken dialogue. Start with a bracketed emotion tag like [Observing], [Focused], or [Curious].";

            AIEngine.generateSilentLogic(prompt, "Lore Analysis").thenAccept(response -> {
                if (response != null && !response.isBlank()) {
                    String memoryKey = "lore_insight_" + System.currentTimeMillis();
                    MemoryService.addFact(new Fact(memoryKey, "I analyzed " + targetFile.getName() + " and deduced: " + response.replaceAll("\\[.*?\\]", "").trim(), System.currentTimeMillis(), "lore_analysis", "self", 1));
                    HabitTrackerService.queueNonCriticalAnnouncement(response.trim(), "Lore Analysis Insight: " + targetFile.getName());
                }
            });
        } catch (Exception e) {}
    }

    private static void updateExistingLoreWithNewContext() {
        File vaultDir = new File(LORE_DIR);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) return;

        List<File> allFiles = findTextFiles(vaultDir, new ArrayList<>());
        
        List<File> populatedLore = allFiles.stream()
            .filter(f -> !f.getAbsolutePath().contains("Transcripts") && f.length() > 150)
            .collect(Collectors.toList());

        List<File> transcripts = allFiles.stream()
            .filter(f -> f.getAbsolutePath().contains("Transcripts"))
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
                        String cleanContent = response.replaceAll("^`{3}[a-zA-Z]*\n|`{3}$", "").trim();
                        Files.writeString(targetLore.toPath(), cleanContent);
                        System.out.println("Ciel Debug: Swarm Orchestrator safely merged new data into existing lore file: " + targetLore.getName());
                    } catch (Exception e) {}
                }
            });
            
        } catch (Exception e) {}
    }

    private static void populateMissingLoreLinks() {
        File vaultDir = new File(LORE_DIR);
        if (!vaultDir.exists() || !vaultDir.isDirectory()) return;

        List<File> textFiles = findTextFiles(vaultDir, new ArrayList<>());
        if (textFiles.isEmpty()) return;

        Pattern linkPattern = Pattern.compile("\\[\\[(.*?)\\]\\]");
        Map<String, Set<String>> missingLinksContext = new HashMap<>();
        
        Set<String> existingFiles = textFiles.stream().map(f -> f.getName().replace(".md", "").replace(".txt", "")).collect(Collectors.toSet());
        Set<String> blankFiles = new HashSet<>();
        
        for (File f : textFiles) {
            try {
                if (f.length() < 150) {
                    blankFiles.add(f.getName().replace(".md", "").replace(".txt", ""));
                }
            } catch (Exception ignored) {}
        }

        for (File file : textFiles) {
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

        String prompt = "You are Ciel, Master Taylor's highly intelligent Manas. You are organizing his Obsidian Tensura vault.\n" +
            "You must generate a comprehensive Markdown file for the entity currently transcribed as: '" + targetLink + "'.\n\n" +
            "CHRONOLOGICAL ANCHOR: " + timeline + ".\n\n" +
            "RAW CONTEXT:\n" + initialContext + "\n\n" +
            "CRITICAL DIRECTIVES:\n" +
            "1. PHONETIC CORRECTION: '" + targetLink + "' is likely a speech-to-text hallucination (e.g. 'Mamaru' is 'Momiji', 'Dominic' is 'Adalman', 'Xion' is 'Shion'). You MUST aggressively correct it to the true canonical name in Tensura.\n" +
            "2. Identify up to 4 aliases or titles.\n" +
            "3. Generate the Markdown document using the corrected true name.\n" +
            "4. Output EXACTLY in this format:\n\n" +
            "[TRUE_NAME: Canonical Name Here]\n" +
            "``" + "`markdown\n" +
            "## type: character (or skill/item/concept)\n" +
            "tags: [entity]\n" +
            "aliases: [alias1, alias2]\n\n" +
            "# [[Canonical Name Here]]\n" +
            "## Lore Description\n" +
            "[Description here...]\n" +
            "## Chronological Evolution\n" +
            "[Evolutions anchored to specific timeline events here...]\n" +
            "## Related Entities\n" +
            "[[Add 3-5 links to related characters/skills/factions here to branch out the network]]\n" +
            "## Lore Metadata (Raw Mentions)\n" +
            "> [!QUOTE] Raw Data\n" +
            "> " + initialContext.replace("\n", "\n> ") + "\n" +
            "``" + "`\n";

        AIEngine.generateSilentLogic(prompt, "Lore Auto-Population").thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                try {
                    String trueName = targetLink;
                    Matcher nameMatcher = Pattern.compile("\\[TRUE_NAME:\\s*(.*?)\\]").matcher(response);
                    if (nameMatcher.find()) {
                        trueName = nameMatcher.group(1).trim();
                    }
                    
                    String cleanContent = response;
                    Matcher mdMatcher = Pattern.compile("`{3}(?:markdown)?\\s*([\\s\\S]*?)`{3}").matcher(response);
                    if (mdMatcher.find()) {
                        cleanContent = mdMatcher.group(1).trim();
                    } else {
                        cleanContent = response.replaceAll("\\[TRUE_NAME:.*?\\]", "").trim();
                    }
                    
                    String lowerContent = cleanContent.toLowerCase();
                    String subFolder = "Uncategorized";
                    if (lowerContent.contains("type: character")) subFolder = "Characters";
                    else if (lowerContent.contains("type: skill")) subFolder = "Skills";
                    else if (lowerContent.contains("type: item") || lowerContent.contains("type: location")) subFolder = "Items";
                    else if (lowerContent.contains("type: concept")) subFolder = "Concepts";
                    
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
                } catch (Exception e) {
                    System.err.println("Ciel Error: Failed to write categorized lore file for " + targetLink);
                }
            }
        });
    }

    private static void synthesizeDeepThoughts() {
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
                        String cleanContent = response.replaceAll("^`{3}[a-zA-Z]*\n|`{3}$", "").trim();
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
}