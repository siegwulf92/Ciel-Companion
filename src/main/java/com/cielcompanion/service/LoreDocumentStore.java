package com.cielcompanion.service;

import com.cielcompanion.memory.DatabaseManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Local, source-grounded LN retrieval for Java. Raw text never enters synced facts or GitHub. */
public final class LoreDocumentStore {
    public static final int MAX_LN_VOLUME = 23;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "from", "about", "what",
            "who", "how", "does", "did", "was", "were", "volume", "tensura", "light", "novel");
    private static final int CHUNK_CHARS = 5_500;
    private static final String LIBRARY = "Tensura Source Library";

    private LoreDocumentStore() {}

    public static void initialize(Path cielRoot, Path campaignRoot) {
        try (Connection db = DatabaseManager.getConnection()) {
            try (var sql = db.createStatement()) {
                sql.execute("CREATE TABLE IF NOT EXISTS lore_sources (volume INTEGER PRIMARY KEY, sha256 TEXT NOT NULL, source_path TEXT NOT NULL)");
                sql.execute("CREATE TABLE IF NOT EXISTS lore_documents (volume INTEGER NOT NULL, chunk_index INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(volume,chunk_index))");
                sql.execute("CREATE INDEX IF NOT EXISTS idx_lore_documents_volume ON lore_documents(volume)");
            }
            Path generated = campaignRoot == null ? null : campaignRoot.resolve("02 dm notes")
                    .resolve(LIBRARY).resolve("Ciel Generated");
            try { prepareLocalLinks(cielRoot, generated); }
            catch (IOException e) {
                System.err.println("Ciel Lore DB: local source links unavailable: " + e.getMessage());
            }
            if (generated != null && Files.isDirectory(campaignRoot)) {
                try { mirror(cielRoot, generated); }
                catch (IOException e) {
                    System.err.println("Ciel Lore DB: campaign mirror unavailable; local indexing continues: " + e.getMessage());
                }
            }
            for (int volume = 1; volume <= MAX_LN_VOLUME; volume++) {
                Path source = findSource(cielRoot, generated, volume);
                if (source != null) indexVolume(db, source, volume);
            }
            System.out.println("Ciel Lore DB: indexed " + indexedVolumeCount(db)
                    + " LN volumes into the local Java SQLite database.");
        } catch (Exception e) {
            System.err.println("Ciel Lore DB: indexing failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    public static void mirror(Path cielRoot, Path generated) throws IOException {
        Path marker = generated.resolve("README_CIEL_GENERATED.md");
        if (Files.exists(generated) && !Files.exists(marker)) {
            throw new IOException("existing campaign library is not marked as Ciel-generated: " + generated);
        }
        Files.createDirectories(generated);
        if (!Files.exists(marker)) Files.writeString(marker,
                "# Ciel Tensura source library\n\nDM-only reference copy. Ciel refreshes files in this folder from its local lore vault. "
                + "Edit campaign canon elsewhere; AI candidate facts remain review-required.\n", StandardCharsets.UTF_8);
        for (int volume = 1; volume <= MAX_LN_VOLUME; volume++) {
            Path source = cielRoot.resolve("lore").resolve("Raw")
                    .resolve("LN Volume " + volume + ".md");
            if (!Files.isRegularFile(source)) source = findSource(cielRoot, null, volume);
            if (source != null) copyChanged(source, generated.resolve("Raw").resolve("LN Volume " + volume + ".md"));
        }
        mirrorTree(cielRoot.resolve("lore").resolve("Transcripts"), generated.resolve("Transcripts"));
        mirrorTree(cielRoot.resolve("lore").resolve("Indexes"), generated.resolve("Indexes"));
        mirrorTree(cielRoot.resolve("lore").resolve("Review").resolve("Tensura"),
                generated.resolve("Review").resolve("Tensura"));
        ensureIndexRawLinks(generated.resolve("Indexes"), generated.resolve("Raw"));
    }

    private static void prepareLocalLinks(Path cielRoot, Path generated) throws IOException {
        Path raw = cielRoot.resolve("lore").resolve("Raw");
        for (int volume = 1; volume <= MAX_LN_VOLUME; volume++) {
            Path source = findSource(cielRoot, generated, volume);
            if (source != null) copyChanged(source, raw.resolve("LN Volume " + volume + ".md"));
        }
        ensureIndexRawLinks(cielRoot.resolve("lore").resolve("Indexes"), raw);
    }

    private static void ensureIndexRawLinks(Path indexes, Path raw) throws IOException {
        if (!Files.isDirectory(indexes)) return;
        for (int volume = 1; volume <= MAX_LN_VOLUME; volume++) {
            Path source = raw.resolve("LN Volume " + volume + ".md");
            Path index = indexes.resolve("LN Volume " + volume + " Master Index.md");
            if (!Files.isRegularFile(source) || !Files.isRegularFile(index)) continue;
            String content = Files.readString(index, StandardCharsets.UTF_8);
            String link = "Raw novel source: [[../Raw/LN Volume " + volume + "]]";
            if (content.contains(link)) continue;
            String newline = content.contains("\r\n") ? "\r\n" : "\n";
            int transcript = content.indexOf("Source transcript:");
            int endLine = transcript < 0 ? -1 : content.indexOf('\n', transcript);
            String updated = endLine < 0 ? content + newline + link + newline
                    : content.substring(0, endLine + 1) + link + newline + content.substring(endLine + 1);
            Path temp = index.resolveSibling(index.getFileName() + ".ciel.tmp");
            Files.writeString(temp, updated, StandardCharsets.UTF_8);
            Files.move(temp, index, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void mirrorTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".md")).toList()) {
                copyChanged(file, target.resolve(source.relativize(file)));
            }
        }
    }

    private static void copyChanged(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (!Files.exists(target) || (Files.mismatch(source, target) != -1
                && Files.getLastModifiedTime(source).toMillis() > Files.getLastModifiedTime(target).toMillis())) {
            Path temp = target.resolveSibling(target.getFileName() + ".ciel.tmp");
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path findSource(Path cielRoot, Path generated, int volume) {
        String file = "LN Volume " + volume + ".md";
        return List.of(cielRoot.resolve("requests").resolve(file),
                cielRoot.resolve("requests").resolve("archive").resolve(file),
                cielRoot.resolve("lore").resolve("Raw").resolve(file),
                generated == null ? cielRoot.resolve("__absent__") : generated.resolve("Raw").resolve(file))
                .stream().filter(Files::isRegularFile)
                .max(Comparator.comparingLong(path -> {
                    try { return Files.getLastModifiedTime(path).toMillis(); }
                    catch (IOException e) { return 0L; }
                })).orElse(null);
    }

    private static void indexVolume(Connection db, Path source, int volume) throws Exception {
        String content = Files.readString(source, StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(digest);
        try (PreparedStatement query = db.prepareStatement("SELECT sha256 FROM lore_sources WHERE volume=?")) {
            query.setInt(1, volume);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next() && hash.equals(rows.getString(1))) return;
            }
        }
        db.setAutoCommit(false);
        try (PreparedStatement delete = db.prepareStatement("DELETE FROM lore_documents WHERE volume=?");
             PreparedStatement insert = db.prepareStatement("INSERT INTO lore_documents(volume,chunk_index,body) VALUES(?,?,?)");
             PreparedStatement sourceRow = db.prepareStatement(
                     "INSERT OR REPLACE INTO lore_sources(volume,sha256,source_path) VALUES(?,?,?)")) {
            delete.setInt(1, volume);
            delete.executeUpdate();
            int offset = 0;
            int chunk = 1;
            while (offset < content.length()) {
                int end = Math.min(content.length(), offset + CHUNK_CHARS);
                if (end < content.length()) {
                    int newline = content.lastIndexOf('\n', end);
                    int space = content.lastIndexOf(' ', end);
                    if (newline > offset + CHUNK_CHARS / 2) end = newline;
                    else if (space > offset + CHUNK_CHARS / 2) end = space;
                }
                insert.setInt(1, volume);
                insert.setInt(2, chunk++);
                insert.setString(3, content.substring(offset, end));
                insert.addBatch();
                offset = end;
            }
            insert.executeBatch();
            sourceRow.setInt(1, volume);
            sourceRow.setString(2, hash);
            sourceRow.setString(3, source.toString());
            sourceRow.executeUpdate();
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    public static String search(String question) {
        List<String> terms = new ArrayList<>();
        Matcher words = WORD.matcher(question == null ? "" : question.toLowerCase(Locale.ROOT));
        while (words.find() && terms.size() < 6) {
            String word = words.group();
            if (!STOP.contains(word) && !terms.contains(word)) terms.add(word);
        }
        if (terms.isEmpty()) return "";
        List<Hit> hits = new ArrayList<>();
        try (Connection db = DatabaseManager.getConnection();
             PreparedStatement query = db.prepareStatement(
                     "SELECT volume,chunk_index,body FROM lore_documents");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String body = rows.getString(3);
                String lower = body.toLowerCase(Locale.ROOT);
                int score = 0;
                int first = -1;
                for (String term : terms) {
                    int position = lower.indexOf(term);
                    if (position >= 0) {
                        score += 5;
                        if (first < 0 || position < first) first = position;
                        if (lower.indexOf(term, position + term.length()) >= 0) score++;
                    }
                }
                if (score > 0) hits.add(new Hit(rows.getInt(1), rows.getInt(2), score,
                        excerpt(body, first)));
            }
        } catch (SQLException e) {
            System.err.println("Ciel Lore DB: retrieval unavailable: " + e.getMessage());
            return "";
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparingInt(Hit::volume).thenComparingInt(Hit::chunk));
        StringBuilder context = new StringBuilder("[TENSURA LIGHT NOVEL SOURCE EXCERPTS; DM REFERENCE, SPOILERS POSSIBLE]\n"
                + "These are raw source passages, not approved campaign canon. Cite the volume and excerpt.\n");
        for (int i = 0; i < Math.min(4, hits.size()); i++) {
            Hit hit = hits.get(i);
            context.append("\n[LN Volume ").append(hit.volume()).append(", excerpt ")
                    .append(hit.chunk()).append("] ").append(hit.excerpt()).append('\n');
        }
        return hits.isEmpty() ? "" : context.toString();
    }

    private static String excerpt(String body, int position) {
        int start = Math.max(0, position - 420);
        int end = Math.min(body.length(), start + 1050);
        return (start > 0 ? "…" : "") + body.substring(start, end).replaceAll("\\s+", " ").trim()
                + (end < body.length() ? "…" : "");
    }

    private static int indexedVolumeCount(Connection db) throws SQLException {
        try (var sql = db.createStatement(); ResultSet rows = sql.executeQuery("SELECT count(*) FROM lore_sources")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private record Hit(int volume, int chunk, int score, String excerpt) {}
}
