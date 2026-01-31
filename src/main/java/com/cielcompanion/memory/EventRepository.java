package com.cielcompanion.memory;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class EventRepository {

    public void addEvent(Event event) {
        String sql = "INSERT INTO events(ts_ms, type, payload_json) VALUES(?,?,?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, event.tsMs());
            pstmt.setString(2, event.type());
            pstmt.setString(3, event.payloadJson());
            pstmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to add event to database.");
            e.printStackTrace();
        }
    }
}
