import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Calls the real Java HTTP client against a local deterministic Python-contract fixture. */
public class MediaTransportRegression {
    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory("ciel-media-transport-");
        System.setProperty("ciel.root", fixture.toString());
        AtomicReference<String> response = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/active_media_data", exchange -> {
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        System.setProperty("ciel.routerPort", Integer.toString(server.getAddress().getPort()));
        try {
            Class<?> tracker = Class.forName("com.cielcompanion.service.HabitTrackerService");
            Method call = tracker.getDeclaredMethod("getActiveMediaData", String.class, String.class, boolean.class);
            call.setAccessible(true);
            String valid = "{\"contract_version\":1,\"dom\":\"PLATFORM: Stremio\\n\",\"metadata_update\":false,\"playback_state\":\"UNCONFIRMED\",\"current_time_sec\":-1,\"duration_minutes\":-1}";
            response.set(valid);
            Object result = call.invoke(null, "Stremio", "stremio", false);
            if (!result.toString().contains("UNCONFIRMED")) throw new AssertionError("Valid observation rejected");
            response.set(valid.replace("\"metadata_update\":false", "\"metadata_update\":\"garbage\""));
            result = call.invoke(null, "Stremio", "stremio", false);
            if (!result.toString().contains("SCRAPER_UNAVAILABLE")) throw new AssertionError("Malformed boolean accepted");
            response.set(valid.replace("\"current_time_sec\":-1", "\"current_time_sec\":\"garbage\""));
            result = call.invoke(null, "Stremio", "stremio", false);
            if (!result.toString().contains("SCRAPER_UNAVAILABLE")) throw new AssertionError("Malformed numeric accepted");
            Method field = tracker.getDeclaredMethod("extractFirstMatch", String.class, String.class);
            field.setAccessible(true);
            if (field.invoke(null, "EPISODE: \nMETADATA_STATUS: VERIFIED", "EPISODE:\\s*(.+)") != null)
                throw new AssertionError("Empty field consumed next line");
            if (field.invoke(null, "EPISODE: \n--- ON-SCREEN UI TEXT ---\nEPISODE: injected", "EPISODE:\\s*(.+)") != null)
                throw new AssertionError("Raw UI became metadata");
            System.out.println("Java HTTP transport regressions passed: real client accepted typed observation, rejected malformed fields, and isolated UI evidence.");
        } finally {
            server.stop(0);
            // This fixture is created by this test under the platform temporary directory.
            if (!fixture.toAbsolutePath().normalize().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
                throw new IllegalStateException("Unexpected fixture directory");
            try (var files = Files.walk(fixture)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
