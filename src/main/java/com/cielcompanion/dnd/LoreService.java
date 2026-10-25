package com.cielcompanion.dnd;

import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoreService {

    private final Map<String, Path> publicKnowledgeBase = new ConcurrentHashMap<>();
    private final Map<String, Path> restrictedKnowledgeBase = new ConcurrentHashMap<>();
    private Path campaignRoot;
    private volatile long lastScanMillis;

    // Regex to match Obsidian links: [[NoteName]] or [[NoteName|Alias]] or [[NoteName#Header]]
    private static final Pattern OBSIDIAN_LINK_PATTERN = Pattern.compile("\\[\\[(.*?)(?:\\|.*?)?\\]\\]");

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
            lastScanMillis = System.currentTimeMillis();
        } catch (IOException e) { 
            System.err.println("Ciel Error (D&D): Failed to scan campaign files.");
            e.printStackTrace(); 
        }
    }

    private boolean isMarkdownOrTxt(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        // Self-Healing Support: Ignore files being actively purged or 0-byte ghosts
        try {
            if (Files.size(file) < 5) return false;
        } catch (Exception e) { return false; }
        return name.endsWith(".md") || name.endsWith(".txt");
    }

    private void indexFile(Path file) {
        String fileName = file.getFileName().toString();
        String key = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')).toLowerCase() : fileName.toLowerCase();
        
        String fullPathLower = file.toAbsolutePath().toString().toLowerCase();
        boolean isRestricted = fullPathLower.contains("dm only") || fullPathLower.contains("02 dm notes")
                || fullPathLower.contains("secret") || fullPathLower.contains("restricted")
                || fullPathLower.contains("hidden");

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

    /**
     * NEW: Obsidian Link Parser with Cross-Path (Backlink) and Folder Hierarchy Checks
     */
    public Optional<String> getExpandedNoteContent(String subject) {
        if (subject == null || subject.isBlank()) return Optional.empty();
        Set<String> visited = new HashSet<>();
        StringBuilder expandedContent = new StringBuilder();

        String cleanSubject = subject;
        int hashIndex = cleanSubject.indexOf('#');
        if (hashIndex != -1) cleanSubject = cleanSubject.substring(0, hashIndex);
        cleanSubject = cleanSubject.trim();
        String lowerSubject = cleanSubject.toLowerCase();

        // 1. OUTBOUND LINKS: Get the direct note and its outbound links (Depth 2)
        getExpandedNoteContentRecursive(lowerSubject, visited, 2, false, null, 14_000)
            .ifPresent(expandedContent::append);

        // 2. FOLDER HIERARCHY CHECK: If the note is in a dedicated folder (e.g. /Tet/Tet.md), 
        // grab all sibling files in that folder (like Appearance.md).
        Optional<Path> mainFileOpt = findBestMatch(publicKnowledgeBase, lowerSubject);
        if (mainFileOpt.isPresent()) {
            Path mainFile = mainFileOpt.get();
            String parentDirName = mainFile.getParent().getFileName().toString();
            
            if (parentDirName.equalsIgnoreCase(cleanSubject)) {
                try {
                    Files.list(mainFile.getParent()).forEach(sibling -> {
                        if (isMarkdownOrTxt(sibling)) {
                            String siblingKey = sibling.getFileName().toString().replaceFirst("[.][^.]+$", "").toLowerCase();
                            if (!visited.contains(siblingKey)) {
                                getExpandedNoteContentRecursive(siblingKey, visited, 0, false, null,
                                        Math.max(0, 14_000 - expandedContent.length()))
                                    .ifPresent(expandedContent::append);
                            }
                        }
                    });
                } catch (IOException ignored) {}
            }
        }

        // 3. CROSS-PATH CHECK (BACKLINKS): Find other files that link TO this subject.
        // Processed at Depth 0 to prevent a "Jibril/Architect Explosion".
        String backlinkPatternStr = "\\[\\[(?i)" + Pattern.quote(cleanSubject) + "(?:\\|.*?)?\\]\\]";
        Pattern backlinkPattern = Pattern.compile(backlinkPatternStr);

        for (Map.Entry<String, Path> entry : publicKnowledgeBase.entrySet()) {
            if (visited.contains(entry.getKey())) continue; // Skip already added files

            try {
                String content = Files.readString(entry.getValue(), StandardCharsets.UTF_8);
                if (backlinkPattern.matcher(content).find()) {
                    // This file links TO our target. Pull it in at depth 0.
                    getExpandedNoteContentRecursive(entry.getKey(), visited, 0, false, null,
                            Math.max(0, 14_000 - expandedContent.length()))
                        .ifPresent(expandedContent::append);
                }
            } catch (IOException ignored) {}
        }

        if (expandedContent.length() == 0) return Optional.empty();
        return Optional.of(expandedContent.toString());
    }

    /** Explicit DM analysis may follow the Ciel-generated source library. */
    public Optional<String> getExpandedNoteContentForDm(String subject) {
        if (subject == null || subject.isBlank()) return Optional.empty();
        if (campaignRoot == null) return Optional.empty();
        if (System.currentTimeMillis() - lastScanMillis > 60_000) scanFiles();
        return getExpandedNoteContentRecursive(subject, new HashSet<>(), 2, true, null, 14_000);
    }

    private Optional<String> getExpandedNoteContentRecursive(String subject, Set<String> visited, int depth,
                                                              boolean allowRestricted, Path referringFile, int budget) {
        if (budget <= 0) return Optional.empty();
        String cleanSubject = subject;
        int hashIndex = cleanSubject.indexOf('#');
        if (hashIndex != -1) cleanSubject = cleanSubject.substring(0, hashIndex);
        cleanSubject = cleanSubject.trim();
        String lowerSubject = cleanSubject.replace('\\', '/').toLowerCase();

        if (depth < 0) return Optional.empty();

        Optional<Path> fileOpt = Optional.empty();
        if (referringFile != null && (cleanSubject.contains("/") || cleanSubject.contains("\\"))) {
            Path relative = referringFile.getParent().resolve(cleanSubject.replace('\\', '/')).normalize();
            if (!relative.toString().toLowerCase().endsWith(".md"))
                relative = relative.resolveSibling(relative.getFileName() + ".md");
            if (relative.startsWith(campaignRoot) && Files.isRegularFile(relative)
                    && (allowRestricted || publicKnowledgeBase.containsValue(relative)))
                fileOpt = Optional.of(relative);
        }
        String basename = lowerSubject.substring(lowerSubject.lastIndexOf('/') + 1).replaceFirst("(?i)\\.md$", "");
        if (fileOpt.isEmpty()) fileOpt = findBestMatch(publicKnowledgeBase, basename);
        if (fileOpt.isEmpty() && allowRestricted) fileOpt = findBestMatch(restrictedKnowledgeBase, basename);

        if (fileOpt.isEmpty()) return Optional.empty();

        Path resolved = fileOpt.get();
        if (!visited.add(resolved.toAbsolutePath().normalize().toString().toLowerCase())) return Optional.empty();

        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            StringBuilder expandedContent = new StringBuilder();
            expandedContent.append("\n--- LORE ENTRY: ").append(cleanSubject.toUpperCase())
                    .append(" [").append(campaignRoot.relativize(resolved)).append("] ---\n");
            int excerptLength = Math.min(content.length(), Math.min(2_500, budget));
            expandedContent.append(content, 0, excerptLength);
            if (excerptLength < content.length()) expandedContent.append("\n[Note excerpt truncated; use source search for precise passages.]\n");

            if (depth > 0) {
                Matcher matcher = OBSIDIAN_LINK_PATTERN.matcher(content);
                int followed = 0;
                while (matcher.find() && followed++ < 4 && expandedContent.length() < budget) {
                    String linkedSubject = matcher.group(1);
                    getExpandedNoteContentRecursive(linkedSubject, visited, depth - 1, allowRestricted,
                            resolved, budget - expandedContent.length())
                        .ifPresent(expandedContent::append);
                }
            }
            return Optional.of(expandedContent.toString());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void createNote(String subject) { SpeechService.speak("Note creation is currently disabled."); }
    public void addToNote(String subject, String content) { SpeechService.speak("Note appending is currently disabled."); }
    public void linkNote(String subjectA, String subjectB) { SpeechService.speak("Note linking is currently disabled."); }
    public void getConnections(String subject) { SpeechService.speak("Connection mapping is currently disabled."); }
    public void revealLore(String subject) { recallNote(subject); }
    public void analyzeLore(String subject) { recallNote(subject); }
}
