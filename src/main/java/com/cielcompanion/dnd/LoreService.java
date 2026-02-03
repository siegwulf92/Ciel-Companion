package com.cielcompanion.dnd;

import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class LoreService {

    // Maps: "friendly name" -> Path to file
    private final Map<String, Path> publicKnowledgeBase = new ConcurrentHashMap<>();
    private final Map<String, Path> restrictedKnowledgeBase = new ConcurrentHashMap<>();
    
    private Path campaignRoot;

    public LoreService() {
        initialize();
    }

    public void initialize() {
        String pathStr = Settings.getDndCampaignPath();
        if (pathStr == null || pathStr.isBlank()) {
            System.err.println("Ciel Warning: D&D Campaign path not set in settings.");
            return;
        }

        campaignRoot = Paths.get(pathStr);
        if (!Files.exists(campaignRoot)) {
            System.err.println("Ciel Warning: D&D Campaign path does not exist: " + campaignRoot);
            return;
        }

        System.out.println("Ciel Debug (D&D): Scanning campaign files in " + campaignRoot);
        scanFiles();
    }

    private void scanFiles() {
        publicKnowledgeBase.clear();
        restrictedKnowledgeBase.clear();

        try {
            Files.walkFileTree(campaignRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isMarkdownOrTxt(file)) {
                        indexFile(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Continue scanning even into restricted folders
                    return FileVisitResult.CONTINUE;
                }
            });
            System.out.printf("Ciel Debug (D&D): Scan complete. Indexed %d public files and %d restricted files.%n", 
                publicKnowledgeBase.size(), restrictedKnowledgeBase.size());
        } catch (IOException e) {
            System.err.println("Ciel Error (D&D): Failed to scan campaign files.");
            e.printStackTrace();
        }
    }

    private boolean isMarkdownOrTxt(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".txt");
    }

    private void indexFile(Path file) {
        String fileName = file.getFileName().toString();
        // Key is filename without extension, lowercased for fuzzy matching
        String key = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')).toLowerCase() : fileName.toLowerCase();
        
        // Check full path for DM markers
        String fullPathLower = file.toAbsolutePath().toString().toLowerCase();
        boolean isRestricted = fullPathLower.contains("dm only") || 
                               fullPathLower.contains("dmonly") || 
                               fullPathLower.contains("dm_only") ||
                               fullPathLower.contains("secret") ||
                               fullPathLower.contains("restricted") ||
                               fullPathLower.contains("hidden");

        if (isRestricted) {
            restrictedKnowledgeBase.put(key, file);
        } else {
            publicKnowledgeBase.put(key, file);
        }
    }

    // --- Public Interaction ---

    public void recallNote(String subject) {
        if (subject == null) return;
        String key = subject.toLowerCase().trim();

        // 1. Try exact/fuzzy match in Public
        Optional<Path> match = findBestMatch(publicKnowledgeBase, key);
        
        if (match.isPresent()) {
            readAndSpeak(match.get(), false);
        } else {
            // 2. Check Restricted (Check existence ONLY, do not read)
            Optional<Path> secretMatch = findBestMatch(restrictedKnowledgeBase, key);
            if (secretMatch.isPresent()) {
                SpeechService.speakPreformatted("That information exists, but is classified under Dungeon Master authority. Authorization required.");
            } else {
                SpeechService.speakPreformatted("I have no records regarding " + subject + " in the public archives.");
            }
        }
    }
    
    /**
     * Call this via a specific privileged command if implemented later.
     */
    public void overrideRecallNote(String subject) {
        if (subject == null) return;
        String key = subject.toLowerCase().trim();
        Optional<Path> match = findBestMatch(restrictedKnowledgeBase, key);
        
        if (match.isPresent()) {
            readAndSpeak(match.get(), true);
        } else {
            recallNote(subject); // Fallback to public check if not found in secret
        }
    }

    private Optional<Path> findBestMatch(Map<String, Path> database, String searchKey) {
        // 1. Exact match
        if (database.containsKey(searchKey)) return Optional.of(database.get(searchKey));

        // 2. Contains match (e.g. "Steph" matches "Stephanie Appearance")
        // We sort by length to find the most specific match first if possible, or just first found
        return database.entrySet().stream()
                .filter(e -> e.getKey().contains(searchKey))
                .findFirst()
                .map(Map.Entry::getValue);
    }

    private void readAndSpeak(Path file, boolean isSecret) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            
            // Basic cleanup of Markdown/Obsidian syntax for TTS
            String speakable = content.replaceAll("#", "") // Remove headers
                                      .replaceAll("\\*", "") // Remove bold/italic
                                      .replaceAll("- ", "") // Remove list bullets
                                      .replaceAll("\\[\\[.*?\\]\\]", "link") // Remove wikilinks [[Link]]
                                      .replaceAll("\\[.*?\\]\\(.*?\\)", "link"); // Remove md links [Text](Url)
            
            // Limit length for sanity
            if (speakable.length() > 600) {
                speakable = speakable.substring(0, 600) + "... there is more data, master.";
            }

            if (isSecret) {
                SpeechService.speakPreformatted("Restricted file accessed: " + speakable);
            } else {
                SpeechService.speakPreformatted(speakable);
            }

        } catch (IOException e) {
            SpeechService.speakPreformatted("I located the file, but the data appears corrupted.");
            e.printStackTrace();
        }
    }

    // --- Stub methods for CommandService calls (Read-Only for now) ---
    public void createNote(String subject) { SpeechService.speak("Note creation is read-only in this version."); }
    public void addToNote(String subject, String content) { SpeechService.speak("Note appending is read-only."); }
    public void linkNote(String subjectA, String subjectB) { SpeechService.speak("Link creation is read-only."); }
    public void getConnections(String subject) { SpeechService.speak("Connection analysis not yet implemented for file system."); }
    public void revealLore(String subject) { recallNote(subject); } 
    public void analyzeLore(String subject) { recallNote(subject); }
}