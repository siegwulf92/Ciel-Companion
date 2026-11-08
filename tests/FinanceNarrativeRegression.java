import com.cielcompanion.service.FinanceService;
import java.lang.reflect.Method;

/** Regression tests for finance-stage status classification. */
public class FinanceNarrativeRegression {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Method method = FinanceService.class.getDeclaredMethod("isFinanceFailure", String.class);
        method.setAccessible(true);

        String validFallback = "Finance status: validated holdings fallback; no model narrative passed validation this cycle. "
                + "Master, this is my rules-based read of the saved holdings—not a live-market forecast. "
                + "Tracked value is $2,000.00. Your largest ticker exposures are VUG (60%), TMUS (40%). "
                + "I will retry the full strategic review after the model chain recovers. "
                + "(Model status: generated narrative failed validation)";
        String malformedFallback = "Finance status: validated holdings fallback; no model narrative passed validation this cycle.";
        String hardFailure = "Strategic recommendations failed: timeout";

        check(!(Boolean) method.invoke(null, validFallback), "validated fallback must complete the finance run");
        check((Boolean) method.invoke(null, malformedFallback), "malformed fallback must remain retryable");
        check((Boolean) method.invoke(null, hardFailure), "stage failure must remain retryable");
        System.out.println("Finance narrative status regression passed.");
    }
}
