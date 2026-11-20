import com.cielcompanion.ai.AIEngine;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises the Java HTTP formatter contract: one endpoint failure must fail
 * closed (Python owns retries); a later invocation may accept audited output.
 */
public class PhonokanaRetryRegression {
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/katakana", exchange -> {
            int call = calls.incrementAndGet();
            byte[] body;
            int status;
            if (call < 2) {
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
            String unavailable = AIEngine.transliterateToKatakanaSync("exit");
            if (!unavailable.isEmpty() || calls.get() != 1)
                throw new AssertionError("Java must fail closed after one endpoint call; Python owns model retries");
            String result = AIEngine.transliterateToKatakanaSync("exit");
            if (!"エクジット。".equals(result)) throw new AssertionError("Unexpected phonokana: " + result);
            if (calls.get() != 2) throw new AssertionError("Expected one request per invocation, got " + calls.get());
            System.out.println("Phonokana endpoint regression passed: failure closed, no multiplied retries, audited recovery accepted.");
        } finally {
            server.stop(0);
            System.clearProperty("ciel.phonokana.endpoint");
        }
        System.exit(0); // AIEngine intentionally owns background executors.
    }
}
