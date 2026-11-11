import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Regression: a deferred finance attempt must advance the persisted backoff. */
public class FinanceScheduleStateRegression {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("ciel-finance-schedule-");
        System.setProperty("user.dir", temp.toString());
        Class<?> jobType = Class.forName("com.cielcompanion.service.FinanceSchedulePolicy$Job");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object daily = Enum.valueOf((Class<? extends Enum>) jobType.asSubclass(Enum.class), "DAILY_BRIEFING");
        Class<?> service = Class.forName("com.cielcompanion.service.FinanceService");
        var record = service.getDeclaredMethod("recordSchedule", jobType, Boolean.class);
        record.setAccessible(true);
        record.invoke(null, daily, Boolean.FALSE);
        var statePath = service.getDeclaredField("SCHEDULE_STATE");
        statePath.setAccessible(true);
        Path scheduleFile = (Path) statePath.get(null);
        Properties state = new Properties();
        try (var in = Files.newInputStream(scheduleFile)) { state.load(in); }
        check(Long.parseLong(state.getProperty("DAILY_BRIEFING.attempt", "0")) > 0,
                "failed attempt did not persist attempt timestamp");
        check("1".equals(state.getProperty("DAILY_BRIEFING.failures")),
                "failed attempt did not increment failures");
        System.out.println("Finance deferred-attempt backoff regression passed.");
    }
}
