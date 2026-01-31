package com.cielcompanion.dnd;

import com.cielcompanion.memory.DatabaseManager;
import com.cielcompanion.service.LineManager;
import com.cielcompanion.service.SpeechService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class LoreService {

    private final CampaignKnowledgeBase campaignKnowledgeBase;

    public LoreService(CampaignKnowledgeBase campaignKnowledgeBase) {
        this.campaignKnowledgeBase = campaignKnowledgeBase;
    }

    public void createNote(String subject) {
        if (subject == null || subject.isBlank()) return;
        String key = subject.toLowerCase();

        String sql = "INSERT OR IGNORE INTO lore_notes (key, content, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?)";
        long now = System.currentTimeMillis();

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, ""); // Start with empty content
            pstmt.setLong(3, now);
            pstmt.setLong(4, now);
            pstmt.executeUpdate();

            LineManager.getLoreCreateLine()
                    .ifPresent(line -> SpeechService.speak(line.text().replace("{subject}", subject)));

        } catch (Exception e) {
            System.err.println("Ciel Error (LoreService): Failed to create note for " + key);
            e.printStackTrace();
        }
    }

    public void addToNote(String subject, String content) {
        if (subject == null || subject.isBlank() || content == null || content.isBlank()) return;
        String key = subject.toLowerCase();

        String sqlSelect = "SELECT content FROM lore_notes WHERE key = ?";
        String sqlUpdate = "UPDATE lore_notes SET content = ?, updated_at_ms = ? WHERE key = ?";

        try (Connection conn = DatabaseManager.getConnection()) {
            String currentContent = "";
            try (PreparedStatement selectStmt = conn.prepareStatement(sqlSelect)) {
                selectStmt.setString(1, key);
                ResultSet rs = selectStmt.executeQuery();
                if (rs.next()) {
                    currentContent = rs.getString("content");
                } else {
                    createNote(subject);
                }
            }

            String newContent = currentContent.isEmpty() ? content : currentContent + ". " + content;

            try (PreparedStatement updateStmt = conn.prepareStatement(sqlUpdate)) {
                updateStmt.setString(1, newContent);
                updateStmt.setLong(2, System.currentTimeMillis());
                updateStmt.setString(3, key);
                updateStmt.executeUpdate();
            }

            LineManager.getLoreAddLine()
                    .ifPresent(line -> SpeechService.speak(line.text().replace("{subject}", subject)));

        } catch (Exception e) {
            System.err.println("Ciel Error (LoreService): Failed to add to note for " + key);
            e.printStackTrace();
        }
    }

    public void revealLore(String subject) {
        if (subject == null || subject.isBlank()) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        String key = subject.toLowerCase();
        Optional<String> lore = campaignKnowledgeBase.getNoteContent(key);

        if (lore.isPresent() && !lore.get().isBlank()) {
            addToNote(subject, lore.get());
            LineManager.getDndRevealSuccessLine().ifPresent(line ->
                SpeechService.speak(line.text().replace("{subject}", subject)));
        } else {
            LineManager.getLoreRecallFailLine().ifPresent(line ->
                SpeechService.speak(line.text().replace("{subject}", subject)));
        }
    }
    
    public void analyzeLore(String subject) {
        if (subject == null || subject.isBlank()) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        String key = subject.toLowerCase();
        Optional<String> lore = campaignKnowledgeBase.getNoteContent(key);

        if (lore.isPresent() && !lore.get().isBlank()) {
            String content = lore.get();
            LineManager.getDndAnalyzeSuccessLine().ifPresent(line -> SpeechService.speak(line.text()
                    .replace("{subject}", subject)
                    .replace("{content}", content)));
        } else {
            LineManager.getDndAnalyzeFailLine().ifPresent(line -> SpeechService.speak(line.text()
                    .replace("{subject}", subject)));
        }
    }

    public void recallNote(String subject) {
        if (subject == null || subject.isBlank()) return;
        String key = subject.toLowerCase();
        String sql = "SELECT content FROM lore_notes WHERE key = ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && !rs.getString("content").isBlank()) {
                String content = rs.getString("content");
                LineManager.getLoreRecallSuccessLine()
                        .ifPresent(line -> SpeechService.speak(line.text()
                                .replace("{subject}", subject)
                                .replace("{content}", content)));
            } else {
                LineManager.getLoreRecallFailLine()
                        .ifPresent(line -> SpeechService.speak(line.text().replace("{subject}", subject)));
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (LoreService): Failed to recall note for " + key);
            e.printStackTrace();
        }
    }
    
    public void linkNote(String sourceSubject, String targetSubject) {
        if (sourceSubject == null || sourceSubject.isBlank() || targetSubject == null || targetSubject.isBlank()) return;
        String sourceKey = sourceSubject.toLowerCase();
        String targetKey = targetSubject.toLowerCase();

        recallNoteForLinking(sourceKey);
        recallNoteForLinking(targetKey);

        String sql = "INSERT OR IGNORE INTO lore_links (source_key, target_key, created_at_ms) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            long now = System.currentTimeMillis();
            pstmt.setString(1, sourceKey);
            pstmt.setString(2, targetKey);
            pstmt.setLong(3, now);
            pstmt.addBatch();

            pstmt.setString(1, targetKey);
            pstmt.setString(2, sourceKey);
            pstmt.setLong(3, now);
            pstmt.addBatch();
            
            pstmt.executeBatch();
            
            LineManager.getLoreLinkSuccessLine().ifPresent(line -> SpeechService.speak(line.text()
                .replace("{subjectA}", sourceSubject)
                .replace("{subjectB}", targetSubject)));

        } catch (Exception e) {
             System.err.println("Ciel Error (LoreService): Failed to link notes " + sourceKey + " and " + targetKey);
            e.printStackTrace();
        }
    }

    public void getConnections(String subject) {
        if (subject == null || subject.isBlank()) return;
        String key = subject.toLowerCase();
        String sql = "SELECT target_key FROM lore_links WHERE source_key = ?";
        List<String> connections = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            while(rs.next()){
                connections.add(rs.getString("target_key"));
            }

            if(connections.isEmpty()){
                LineManager.getLoreRecallLinksFailLine().ifPresent(line -> SpeechService.speak(line.text().replace("{subject}", subject)));
            } else {
                String connectionList = String.join(", ", connections);
                 LineManager.getLoreRecallLinksSuccessLine().ifPresent(line -> SpeechService.speak(line.text()
                    .replace("{subject}", subject)
                    .replace("{connections}", connectionList)));
            }

        } catch (Exception e) {
            System.err.println("Ciel Error (LoreService): Failed to get connections for " + key);
            e.printStackTrace();
        }
    }

    private void recallNoteForLinking(String subject) {
       String key = subject.toLowerCase();
        String sql = "SELECT key FROM lore_notes WHERE key = ?";
       try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                createNote(subject);
            }
        } catch(Exception e) {
            System.err.println("Ciel Error (LoreService): Failed to check existence for " + key);
        }
    }

    private void speakRandomLine(List<LineManager.DialogueLine> pool) {
        if (pool != null && !pool.isEmpty()) {
            SpeechService.speakPreformatted(pool.get(new Random().nextInt(pool.size())).text());
        }
    }
}

