package com.cielcompanion.memory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class MemoryService {

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

    /**
     * REWORKED: Now saves the phase number along with the spoken line.
     */
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

    /**
     * REWORKED: Retrieves the keys of the 5 most recently spoken lines for a specific phase.
     */
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
