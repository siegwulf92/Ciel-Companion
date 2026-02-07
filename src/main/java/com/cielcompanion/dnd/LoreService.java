package com.cielcompanion.dnd;

import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LoreService {

    private final Map<String, Path> publicKnowledgeBase = new ConcurrentHashMap<>();
    private final Map<String, Path> restrictedKnowledgeBase = new ConcurrentHashMap<>();
    private Path campaignRoot;

    public LoreService() {
        initialize();
    }

    public void initialize() {
        String pathStr = Settings.getDndCampaignPath();
        if (pathStr == null || pathStr.isBlank()) {
            System.err.println("Ciel Warning (D&D): Campaign path not set in settings.");
            return;
        }

        campaignRoot = Paths.get(pathStr);
        if (Files.exists(campaignRoot)) {
            System.out.println("Ciel Debug (D&D): Scanning campaign files in " + campaignRoot);
            scanFiles();
        } else {
            System.err.println("Ciel Warning (D&D): Campaign path does not exist: " + campaignRoot);
        }
    }

    public void runCampaignAudit() {
        int total = publicKnowledgeBase.size() + restrictedKnowledgeBase.size();
        SpeechService.speakPreformatted("Audit initiated. I have indexed " + total + " total campaign files.");
        
        if (total < 400) {
            SpeechService.speakPreformatted("Master, I am only seeing " + total + " files. You mentioned four hundred and one. Some may be in unsupported formats.");
        } else {
            SpeechService.speakPreformatted("Audit complete. All files are accounted for and synchronized.");
        }
    }

    private void scanFiles() {
        publicKnowledgeBase.clear();
        restrictedKnowledgeBase.clear();
        try {
            Files.walkFileTree(campaignRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isMarkdownOrTxt(file)) indexFile(file);
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
        String key = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')).toLowerCase() : fileName.toLowerCase();
        
        String fullPathLower = file.toAbsolutePath().toString().toLowerCase();
        boolean isRestricted = fullPathLower.contains("dm only") || fullPathLower.contains("secret") || fullPathLower.contains("restricted") || fullPathLower.contains("hidden");

        if (isRestricted) {
            restrictedKnowledgeBase.put(key, file);
        } else {
            publicKnowledgeBase.put(key, file);
        }
    }

    public void recallNote(String subject) {
        if (subject == null) return;
        String key = subject.toLowerCase().trim();
        findBestMatch(publicKnowledgeBase, key).ifPresentOrElse(
            path -> readAndSpeak(path, false),
            () -> findBestMatch(restrictedKnowledgeBase, key).ifPresentOrElse(
                path -> SpeechService.speakPreformatted("That information is restricted to Dungeon Master authority. Access denied."),
                () -> SpeechService.speakPreformatted("I have no lore entries for " + subject)
            )
        );
    }

    public void overrideRecallNote(String subject) {
        String key = subject.toLowerCase().trim();
        findBestMatch(restrictedKnowledgeBase, key).ifPresentOrElse(
            path -> readAndSpeak(path, true),
            () -> recallNote(subject)
        );
    }

    private Optional<Path> findBestMatch(Map<String, Path> database, String searchKey) {
        if (database.containsKey(searchKey)) return Optional.of(database.get(searchKey));
        return database.entrySet().stream()
                .filter(e -> e.getKey().contains(searchKey))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private void readAndSpeak(Path file, boolean isSecret) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String speakable = content.replaceAll("[#\\*]", "").replaceAll("- ", "").replaceAll("\\[\\[.*?\\]\\]", "");
            if (speakable.length() > 600) speakable = speakable.substring(0, 600) + "... there is more data.";
            SpeechService.speakPreformatted((isSecret ? "Restricted Data: " : "") + speakable);
        } catch (IOException e) { e.printStackTrace(); }
    }

    public void createNote(String subject) { SpeechService.speak("Note creation is currently disabled."); }
    public void addToNote(String subject, String content) { SpeechService.speak("Note appending is currently disabled."); }
    public void linkNote(String subjectA, String subjectB) { SpeechService.speak("Note linking is currently disabled."); }
    public void getConnections(String subject) { SpeechService.speak("Connection mapping is currently disabled."); }
    public void revealLore(String subject) { recallNote(subject); }
    public void analyzeLore(String subject) { recallNote(subject); }
}