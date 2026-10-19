import com.cielcompanion.service.FinanceService;

public class FinanceStatusRegression {
    public static void main(String[] args) throws Exception {
        var method = FinanceService.class.getDeclaredMethod("isFinanceFailure", String.class);
        method.setAccessible(true);
        String successful = "Portfolio refresh completed: 3/3 priced holding rows refreshed. Unavailable quotes retain their prior prices.";
        if ((boolean) method.invoke(null, successful)) throw new AssertionError("Ordinary explanatory prose incorrectly marks a successful refresh as failed.");
        if ((boolean) method.invoke(null, "The analysis discusses unavailable historical data without claiming a failed pipeline.")) throw new AssertionError("Narrative words must not be used as stage status.");
        for (String failed : new String[] {"Portfolio update failed: connection refused", "Portfolio refresh partial: 2/3", "Strategic recommendations failed: TimeoutException", "Market data unavailable", "Error: CSV is locked", "Master, the recommendation models did not return prose this cycle", "Weekday news watch unavailable: no source coverage", "Finance module error: TypeError"}) {
            if (!(boolean) method.invoke(null, failed)) throw new AssertionError("Missed explicit failure marker: " + failed);
        }
        System.out.println("Finance stage-status regressions passed.");
    }
}
