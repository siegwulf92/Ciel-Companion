package com.cielcompanion.memory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private static final String DB_FILE_NAME = "CielCompanion.db";
    private static final String APP_DATA_DIRECTORY = System.getenv("LOCALAPPDATA") + File.separator + "CielCompanion";
    private static String connectionUrl;

    public static void initialize() {
        try {
            Path dbPath = Paths.get(APP_DATA_DIRECTORY, DB_FILE_NAME);
            Files.createDirectories(dbPath.getParent());
            connectionUrl = "jdbc:sqlite:" + dbPath;
            
            try (Connection conn = DriverManager.getConnection(connectionUrl)) {
                System.out.println("Ciel Debug: Database connection to SQLite has been established for initialization.");
                initializeTables(conn);
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: FAILED to initialize the database manager.");
            e.printStackTrace();
        }
    }

    public static Connection getConnection() throws SQLException {
        if (connectionUrl == null) {
            throw new SQLException("DatabaseManager has not been initialized.");
        }
        return DriverManager.getConnection(connectionUrl);
    }

    private static void initializeTables(Connection conn) {
        String eventsTableSql = """
        CREATE TABLE IF NOT EXISTS events (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          ts_ms INTEGER NOT NULL,
          type TEXT NOT NULL,
          payload_json TEXT NOT NULL,
          processed_at_ms INTEGER
        );
        """;
        
        String factsTableSql = """
        CREATE TABLE IF NOT EXISTS facts (
          key TEXT PRIMARY KEY NOT NULL,
          value TEXT NOT NULL,
          created_at_ms INTEGER NOT NULL,
          tags TEXT,
          source TEXT NOT NULL,
          version INTEGER NOT NULL
        );
        """;

        String speechHistoryTableSql = """
        CREATE TABLE IF NOT EXISTS speech_history (
            line_key TEXT PRIMARY KEY NOT NULL,
            line_text TEXT NOT NULL,
            spoken_at_ms INTEGER NOT NULL,
            phase INTEGER NOT NULL
        );
        """;

        String loreNotesTableSql = """
        CREATE TABLE IF NOT EXISTS lore_notes (
            key TEXT PRIMARY KEY NOT NULL,
            content TEXT NOT NULL,
            created_at_ms INTEGER NOT NULL,
            updated_at_ms INTEGER NOT NULL
        );
        """;

        String loreLinksTableSql = """
        CREATE TABLE IF NOT EXISTS lore_links (
            source_key TEXT NOT NULL,
            target_key TEXT NOT NULL,
            created_at_ms INTEGER NOT NULL,
            PRIMARY KEY (source_key, target_key)
        );
        """;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(eventsTableSql);
            stmt.execute(factsTableSql);
            stmt.execute(speechHistoryTableSql);
            stmt.execute(loreNotesTableSql);
            stmt.execute(loreLinksTableSql);
            System.out.println("Ciel Debug: Database tables checked/initialized successfully.");
        } catch (Exception e) {
            System.err.println("Ciel Error: FAILED to initialize database tables.");
            e.printStackTrace();
        }
    }
}

