import com.cielcompanion.ai.AIEngine;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises the real Java HTTP formatter contract: two bounded failures must
 * be visible and retried, and only a word-aligned Katakana response may win.
 */
public class PhonokanaRetryRegression {
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/katakana", exchange -> {
            int call = calls.incrementAndGet();
            byte[] body;
            int status;
            if (call < 3) {
                status = 503;
                body = "{\"detail\":\"router temporarily unavailable\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = "{\"katakana\":\"エクジット。\",\"audit\":\"word-aligned\",\"status\":\"CONTEXTUAL_MODEL\"}"
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            System.setProperty("ciel.phonokana.endpoint",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/katakana");
            String result = AIEngine.transliterateToKatakanaSync("exit");
            if (!"エクジット。".equals(result)) throw new AssertionError("Unexpected phonokana: " + result);
            if (calls.get() != 3) throw new AssertionError("Expected exactly 3 bounded attempts, got " + calls.get());
            System.out.println("Phonokana retry regression passed: retries were bounded and audited output won.");
        } finally {
            server.stop(0);
            System.clearProperty("ciel.phonokana.endpoint");
        }
        System.exit(0); // AIEngine intentionally owns background executors.
    }
}
