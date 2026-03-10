package com.cielcompanion.dnd;

import com.cielcompanion.service.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CampaignKnowledgeBase {

    private final Map<Path, String> campaignNotes = new ConcurrentHashMap<>();
    private Thread watcherThread;
    private volatile boolean isRunning = true;
    
    // Regex to match Obsidian links: [[NoteName]] or [[NoteName|Alias]] or [[NoteName#Header]]
    private static final Pattern OBSIDIAN_LINK_PATTERN = Pattern.compile("\\[\\[(.*?)(?:\\|.*?)?\\]\\]");

    public void initialize() {
        System.out.println("Ciel Debug (D&D): Initializing Campaign Knowledge Base at: " + Settings.getDndCampaignPath());
        Path campaignPath = Paths.get(Settings.getDndCampaignPath());

        if (Files.isDirectory(campaignPath)) {
            scanDirectory(campaignPath);
            startWatcher(campaignPath);
        } else {
            System.err.println("Ciel Error (D&D): Campaign path is not a valid directory: " + campaignPath);
        }
    }

    private void startWatcher(Path path) {
        watcherThread = new Thread(() -> watchDirectory(path));
        watcherThread.setName("Campaign-Watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        System.out.println("Ciel Debug (D&D): Now actively monitoring campaign folder for changes.");
    }

    private void watchDirectory(Path path) {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            System.out.println("Ciel Debug (D&D): Watch service registered for " + path);

            while (isRunning) {
                WatchKey key;
                try {
                    key = watchService.take(); // This blocks until an event occurs
                } catch (InterruptedException e) {
                    if (!isRunning) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    Path fullPath = path.resolve(changed);
                    
                    if (isValidFile(fullPath)) {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE || event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                            loadFile(fullPath);
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            removeFile(fullPath);
                        }
                    }
                }
                key.reset();
            }
        } catch (IOException e) {
            System.err.println("Ciel Error (D&D): Campaign Knowledge Base watcher failed.");
            e.printStackTrace();
        } finally {
            System.out.println("Ciel Debug (D&D): Campaign file watcher has shut down.");
        }
    }
    
    public void close() {
        isRunning = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }

    private void scanDirectory(Path path) {
        try (Stream<Path> paths = Files.walk(path)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isValidFile)
                 .forEach(this::loadFile);
            System.out.println("Ciel Debug (D&D): Initial scan complete. Loaded " + campaignNotes.size() + " campaign notes.");
        } catch (IOException e) {
            System.err.println("Ciel Error (D&D): Failed to perform initial scan of campaign directory.");
            e.printStackTrace();
        }
    }

    private void loadFile(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            campaignNotes.put(path, content);
            System.out.println("Ciel Debug (D&D): Loaded/Updated campaign note: " + path.getFileName());
        } catch (IOException e) {
            System.err.println("Ciel Error (D&D): Could not read campaign file: " + path);
        }
    }

    private void removeFile(Path path) {
        if (campaignNotes.remove(path) != null) {
            System.out.println("Ciel Debug (D&D): Removed campaign note: " + path.getFileName());
        }
    }
    
    private boolean isValidFile(Path path) {
        String fileName = path.toString().toLowerCase();
        return fileName.endsWith(".txt") || fileName.endsWith(".md");
    }

    public Map<Path, String> getCampaignNotes() {
        return campaignNotes;
    }

    /**
     * Searches the knowledge base for a single specific note (No cross-referencing).
     */
    public Optional<String> getNoteContent(String subject) {
        String lowerSubject = subject.toLowerCase();
        for (Map.Entry<Path, String> entry : campaignNotes.entrySet()) {
            String fileName = entry.getKey().getFileName().toString();
            // Remove file extension for comparison
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0) {
                fileName = fileName.substring(0, dotIndex);
            }
            if (fileName.equalsIgnoreCase(lowerSubject)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * NEW: Obsidian Link Parser
     * Gets a note AND crawls its Obsidian links up to a specific depth to build a mega-context string.
     */
    public Optional<String> getExpandedNoteContent(String subject) {
        Set<String> visited = new HashSet<>();
        // Set depth to 2. Meaning it will read the first note, read any notes it links to, 
        // and read the notes THOSE notes link to. Prevents blowing up the LLM token limit.
        return getExpandedNoteContentRecursive(subject, visited, 2); 
    }

    private Optional<String> getExpandedNoteContentRecursive(String subject, Set<String> visited, int depth) {
        // Obsidian links often use headers like [[NoteName#Header]]. Strip the # header for the file search.
        String cleanSubject = subject;
        int hashIndex = cleanSubject.indexOf('#');
        if (hashIndex != -1) {
            cleanSubject = cleanSubject.substring(0, hashIndex);
        }
        cleanSubject = cleanSubject.trim();

        String lowerSubject = cleanSubject.toLowerCase();

        // Stop if we've already loaded this note (prevents infinite loops) or hit the depth limit
        if (visited.contains(lowerSubject) || depth < 0) {
            return Optional.empty();
        }
        
        Optional<String> baseNoteOpt = getNoteContent(cleanSubject);
        if (baseNoteOpt.isEmpty()) {
            return Optional.empty(); // Note doesn't exist in folder
        }

        // Mark as visited
        visited.add(lowerSubject);

        String content = baseNoteOpt.get();
        StringBuilder expandedContent = new StringBuilder();
        
        // Format nicely for the AI
        expandedContent.append("\n--- LORE ENTRY: ").append(cleanSubject.toUpperCase()).append(" ---\n");
        expandedContent.append(content).append("\n");

        // If we still have depth remaining, find links inside this note and crawl them
        if (depth > 0) {
            Matcher matcher = OBSIDIAN_LINK_PATTERN.matcher(content);
            while (matcher.find()) {
                String linkedSubject = matcher.group(1); // Grabs the inside of [[ ]]
                
                Optional<String> linkedNoteContent = getExpandedNoteContentRecursive(linkedSubject, visited, depth - 1);
                linkedNoteContent.ifPresent(expandedContent::append);
            }
        }

        return Optional.of(expandedContent.toString());
    }
}