package com.cielcompanion.memory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class MemoryService {

    public record ConversationTurn(String role, String content, long createdAtMs) {}

    public static void initialize() {
        try {
            DatabaseManager.initialize();
            System.out.println("Ciel Debug: MemoryService initialized SUCCESSFULLY.");
        } catch (Exception e) {
            System.err.println("Ciel Error: FAILED to initialize MemoryService.");
            e.printStackTrace();
        }
    }

    public static void addFact(Fact fact) {
        String sql = "INSERT OR REPLACE INTO facts(key, value, created_at_ms, tags, source, version) VALUES(?,?,?,?,?,?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fact.key().toLowerCase());
            pstmt.setString(2, fact.value());
            pstmt.setLong(3, fact.createdAtMs());
            pstmt.setString(4, fact.tags());
            pstmt.setString(5, fact.source());
            pstmt.setInt(6, fact.version());
            pstmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to add fact to database.");
            e.printStackTrace();
        }
    }

    public static Optional<Fact> getFact(String key) {
        String sql = "SELECT key, value, created_at_ms, tags, source, version FROM facts WHERE key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key.toLowerCase());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return Optional.of(new Fact(
                    rs.getString("key"),
                    rs.getString("value"),
                    rs.getLong("created_at_ms"),
                    rs.getString("tags"),
                    rs.getString("source"),
                    rs.getInt("version")
                ));
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to retrieve fact from database.");
            e.printStackTrace();
        }
        return Optional.empty();
    }

    // --- ADD THIS NEW METHOD ---
    public static List<Fact> getFactsByTag(String tag) {
        List<Fact> facts = new ArrayList<>();
        String sql = "SELECT key, value, created_at_ms, tags, source, version FROM facts WHERE tags LIKE ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, "%" + tag + "%");
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                facts.add(new Fact(
                    rs.getString("key"),
                    rs.getString("value"),
                    rs.getLong("created_at_ms"),
                    rs.getString("tags"),
                    rs.getString("source"),
                    rs.getInt("version")
                ));
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to retrieve facts by tag.");
            e.printStackTrace();
        }
        return facts;
    }

    // --- NEW: Retrieve Long-Term Episodic Memories ---
    public static List<String> getRecentEpisodicMemories(int limit) {
        List<String> memories = new ArrayList<>();
        String sql = "SELECT value FROM facts WHERE tags = 'episodic_memory' ORDER BY created_at_ms DESC LIMIT ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                memories.add(rs.getString("value"));
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to retrieve episodic memories.");
        }
        return memories;
    }

    public static void recordConversationTurn(String role, String content, long createdAtMs) {
        if (role == null || content == null || content.isBlank()) return;
        String sql = "INSERT INTO conversation_history(role, content, created_at_ms) VALUES(?,?,?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, role);
            pstmt.setString(2, content);
            pstmt.setLong(3, createdAtMs);
            pstmt.executeUpdate();
            try (var cleanup = conn.createStatement()) {
                cleanup.executeUpdate("DELETE FROM conversation_history WHERE id NOT IN (SELECT id FROM conversation_history ORDER BY created_at_ms DESC, role DESC, content DESC LIMIT 5000)");
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to persist conversation turn: " + e.getMessage());
        }
    }

    public static List<ConversationTurn> getRecentConversationTurns(int limit) {
        List<ConversationTurn> turns = new ArrayList<>();
        String sql = "SELECT role, content, created_at_ms FROM conversation_history ORDER BY created_at_ms DESC, role DESC, content DESC LIMIT ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, Math.max(1, limit));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) turns.add(new ConversationTurn(rs.getString("role"), rs.getString("content"), rs.getLong("created_at_ms")));
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to read persisted conversation history: " + e.getMessage());
        }
        return turns;
    }

    public static Optional<Fact> getLatestFactByTag(String tag) {
        String sql = "SELECT key, value, created_at_ms, tags, source, version FROM facts WHERE tags LIKE ? ORDER BY created_at_ms DESC LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, "%" + tag + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return Optional.of(new Fact(rs.getString("key"), rs.getString("value"), rs.getLong("created_at_ms"), rs.getString("tags"), rs.getString("source"), rs.getInt("version")));
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to read latest memory fact for tag '" + tag + "': " + e.getMessage());
        }
        return Optional.empty();
    }

    public static void recordSpokenLine(SpokenLine line) {
        String sql = "INSERT OR REPLACE INTO speech_history(line_key, line_text, spoken_at_ms, phase) VALUES(?,?,?,?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, line.lineKey());
            pstmt.setString(2, line.lineText());
            pstmt.setLong(3, line.spokenAtMs());
            pstmt.setInt(4, line.phase());
            pstmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to record spoken line.");
            e.printStackTrace();
        }
    }

    public static Set<String> getRecentlySpokenLineKeysForPhase(int phase) {
        Set<String> recentKeys = new HashSet<>();
        String sql = "SELECT line_key FROM speech_history WHERE phase = ? ORDER BY spoken_at_ms DESC LIMIT 5";
        
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, phase);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                recentKeys.add(rs.getString("line_key"));
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to retrieve recent speech history for phase " + phase);
            e.printStackTrace();
        }
        return recentKeys;
    }
}
