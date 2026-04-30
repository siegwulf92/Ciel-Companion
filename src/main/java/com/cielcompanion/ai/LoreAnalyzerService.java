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
        
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::analyzeLoreSilently, 45, 120, TimeUnit.MINUTES);
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::populateMissingLoreLinks, 15, 240, TimeUnit.MINUTES);
        
        // --- CRITICAL FIX: The DeepSeek Lore Updater ---
        // Scans existing files every few hours and safely merges new facts from transcripts!
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::updateExistingLoreWithNewContext, 30, 360, TimeUnit.MINUTES);
        
        loreScheduler.scheduleWithFixedDelay(LoreAnalyzerService::synthesizeDeepThoughts, 60, 360, TimeUnit.MINUTES);
        
        System.out.println("Ciel Debug: Deep Lore Analyzer & Obsidian Auto-Population initialized.");
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

    // --- CRITICAL FIX: The Safe Updater (Prevents data loss when updating existing Lore) ---
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

            // This trigger tag forces Python to pass the data directly to DeepSeek (The Orchestrator)
            // ensuring the old lore is perfectly preserved while the new info is injected.
            String prompt = "[UPDATE_LORE]\n" +
                "EXISTING LORE:\n" + existingContent + "\n\n" +
                "NEW MENTIONS/CONTEXT:\n" + newContext;

            AIEngine.generateSilentLogic(prompt, "Lore Evolution").thenAccept(response -> {
                if (response != null && !response.isBlank() && !response.contains("NO_UPDATE_NEEDED")) {
                    try {
                        String cleanContent = response.replaceAll("^```[a-zA-Z]*\n|```$", "").trim();
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

        String aliasPrompt = "You are Ciel from the Tensura universe. Read this context where the entity '" + targetLink + "' was mentioned:\n" +
            "\"" + initialContext + "\"\n\n" +
            "Based on this context and your knowledge, identify up to 4 SPECIFIC alternative names, pseudonyms, or unique titles for '" + targetLink + "'.\n" +
            "CRITICAL RULES:\n" +
            "1. IF TARGET IS A CHARACTER: DO NOT include generic race names, species, or shared classifications (e.g., DO NOT use 'True Dragon', 'Slime', 'Arch Demon', 'Demon Lord', or 'Primordial' as aliases for characters).\n" +
            "2. IF TARGET IS A CLASSIFICATION/RACE (e.g., 'True Dragon', 'Demon'): DO NOT list specific character names (like 'Veldora', 'Rimuru', 'Diablo') as aliases.\n" +
            "3. ONLY use highly specific, unique equivalent identifiers.\n" +
            "Output ONLY a comma-separated list of names. Do not include the original name unless necessary. No conversational text.";

        final List<File> finalTextFiles = textFiles;

        AIEngine.generateSilentLogic(aliasPrompt, "Alias Extraction").thenAccept(aliasResponse -> {
            Set<String> searchTerms = new HashSet<>();
            searchTerms.add(targetLink.toLowerCase());
            
            if (aliasResponse != null && !aliasResponse.isBlank()) {
                String[] aliases = aliasResponse.split(",");
                for (String a : aliases) {
                    String cleanAlias = a.trim().toLowerCase().replaceAll("[\\[\\]\\*\\_]", ""); 
                    if (!cleanAlias.isBlank() && cleanAlias.length() > 2) {
                        searchTerms.add(cleanAlias);
                    }
                }
            }
            
            String aliasesString = String.join(", ", searchTerms);
            System.out.println("Ciel Debug: Dynamic Aliases identified for " + targetLink + ": " + aliasesString);

            Set<String> contextParas = new HashSet<>();
            
            for (File file : finalTextFiles) {
                try {
                    String content = Files.readString(file.toPath());
                    String[] paragraphs = content.split("\\n\\s*\\n");
                    for (String para : paragraphs) {
                        for (String term : searchTerms) {
                            Pattern termPattern = Pattern.compile("(?i)\\b" + Pattern.quote(term) + "\\b");
                            if (termPattern.matcher(para).find()) {
                                contextParas.add(para.trim());
                                break; 
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            String rawContext = contextParas.stream().limit(25).collect(Collectors.joining("\n\n"));

            String prompt = "You are Ciel, Master Taylor's highly intelligent Manas. You are organizing his Obsidian D&D / Tensura vault.\n" +
                "You must generate a comprehensive Markdown file for the missing entity: '[[" + targetLink + "]]'.\n\n" +
                "KNOWN ALIASES/TITLES: " + aliasesString + "\n\n" +
                "RAW CONTEXT (Paragraphs extracted from across the vault where this entity or its aliases were mentioned):\n" +
                rawContext + "\n\n" +
                "INSTRUCTIONS:\n" +
                "1. Deduce if this entity is a Character, Skill, Lore Item, Location, or Concept/Classification (e.g., a race, species, or faction like 'True Dragon' or 'Demon Lord') based on the context.\n" +
                "2. Identify any generic classifications, races, or species. Format them as snake_case and inject them into the 'tags' list.\n" +
                "3. Generate the Markdown document using your immense internal knowledge base.\n" +
                "4. USE THE EXACT TEMPLATES BELOW based on the type you deduce:\n\n" +
                "IF CHARACTER:\n" +
                "## type: character\ntags: [entity, status/unverified, insert_classification_tags_here]\naliases: [" + aliasesString + "]\nalignment: \ncr_estimate: \n\n# [[" + targetLink + "]]\n## Profile\n[Summary here]\n## Unique Observations & Interactions\n[Details here]\n## Abilities\n[Skills here]\n## Campaign Notes\n[Leave blank for Master]\n## Lore Metadata (Raw Mentions)\n\n" +
                "IF SKILL:\n" +
                "## type: skill\ntags: [ability, status/unverified, insert_classification_tags_here]\naliases: [" + aliasesString + "]\nrank: \nsource_entity: \n\n# [[" + targetLink + "]]\n## Mechanics\n[Summary here]\n## D&D 5e Equivalent\n[Spell/Feat comparison here]\n## Combat Data\n[Usage here]\n## Lore Metadata (Raw Mentions)\n\n" +
                "IF ITEM/LOCATION:\n" +
                "## type: item\ntags: [material, status/unverified, insert_classification_tags_here]\naliases: [" + aliasesString + "]\nrarity: \nvalue: \n\n# [[" + targetLink + "]]\n## Lore Description\n[Summary here]\n## D&D Properties\n[Properties here]\n## Source Locations\n[Where it is found]\n## Lore Metadata (Raw Mentions)\n\n" +
                "IF CONCEPT/CLASSIFICATION:\n" +
                "## type: concept\ntags: [lore, status/unverified, insert_classification_tags_here]\naliases: [" + aliasesString + "]\n\n# [[" + targetLink + "]]\n## Lore Description\n[Summary of the race/group/concept here]\n## Notable Members\n[List of known entities belonging to this group]\n## D&D Mechanics\n[Racial traits, faction rules, or mechanics here]\n## Lore Metadata (Raw Mentions)\n\n" +
                "5. Auto-bracket [[ ]] any other significant characters, skills, or locations mentioned in your generated text to retroactively link them to the broader vault.\n" +
                "6. CRITICAL: You MUST place all of the 'RAW CONTEXT' provided above into a `> [!QUOTE] Raw Data` markdown block exactly under the '## Lore Metadata (Raw Mentions)' header.\n" +
                "Output ONLY the raw Markdown content. Do not wrap it in ```markdown fences or include conversational text.";

            AIEngine.generateSilentLogic(prompt, "Lore Auto-Population").thenAccept(response -> {
                if (response != null && !response.isBlank()) {
                    try {
                        String cleanContent = response.replaceAll("^```[a-zA-Z]*\n|```$", "").trim();
                        String lowerContent = cleanContent.toLowerCase();
                        
                        String subFolder = "Uncategorized";
                        if (lowerContent.contains("type: character")) subFolder = "Characters";
                        else if (lowerContent.contains("type: skill")) subFolder = "Skills";
                        else if (lowerContent.contains("type: item") || lowerContent.contains("type: location")) subFolder = "Items";
                        else if (lowerContent.contains("type: concept")) subFolder = "Concepts";
                        
                        File targetDir = new File(LORE_DIR, subFolder);
                        targetDir.mkdirs();
                        
                        String safeFileName = targetLink.replaceAll("[\\\\/:*?\"<>|]", "").replace("**", "").replace("*", "").trim();
                        
                        File oldBlankFile = new File(LORE_DIR, safeFileName + ".md");
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
                        String cleanContent = response.replaceAll("^```[a-zA-Z]*\n|```$", "").trim();
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