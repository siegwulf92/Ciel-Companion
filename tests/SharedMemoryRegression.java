import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.DatabaseManager;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;

/** Run with LOCALAPPDATA pointing at an isolated test directory. No network calls. */
public class SharedMemoryRegression {
    public static void main(String[] args) throws Exception {
        try {
            MemoryService.initialize();
            try (var db = DatabaseManager.getConnection(); var statement = db.createStatement()) {
                statement.execute("CREATE TABLE test_outbox(payload TEXT)");
                statement.execute("CREATE TRIGGER test_json_trigger AFTER INSERT ON facts BEGIN INSERT INTO test_outbox VALUES(json_object('key',NEW.key,'value',NEW.value)); END");
            }
            MemoryService.addFact(new Fact("paired_test", "hello", 100, "user_knowledge", "user", 1));
            try (var db = DatabaseManager.getConnection(); var statement = db.createStatement(); var result = statement.executeQuery("SELECT payload FROM test_outbox")) {
                if (!result.next() || !result.getString(1).contains("paired_test")) throw new AssertionError("JDBC trigger did not capture local fact");
            }
            AIEngine.recordExternalTurn("user", "local conversation");
            // Import an old turn AFTER the new local turn: insertion order is wrong.
            MemoryService.recordConversationTurn("assistant", "old imported turn", 1);
            if (!MemoryService.getRecentConversationTurns(1).getFirst().content().equals("local conversation")) {
                throw new AssertionError("Context must sort by event time, not insertion ID");
            }
            // An arriving peer turn must be visible despite an already populated AI cache.
            MemoryService.recordConversationTurn("assistant", "new peer conversation", System.currentTimeMillis() + 1000);
            Method payloadMethod = java.util.Arrays.stream(AIEngine.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("buildPayloadWithHistory")).findFirst().orElseThrow();
            payloadMethod.setAccessible(true);
            Object tier = payloadMethod.getParameterTypes()[0].getEnumConstants()[0];
            JsonObject payload = (JsonObject) payloadMethod.invoke(null, tier, "test context", false);
            if (!payload.getAsJsonArray("messages").toString().contains("new peer conversation")) {
                throw new AssertionError("AI request did not load arriving peer memory");
            }
            System.out.println("Shared memory Java regressions passed.");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
