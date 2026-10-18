import com.cielcompanion.service.FinanceSchedulePolicy;
import static com.cielcompanion.service.FinanceSchedulePolicy.Job.*;
import java.time.ZonedDateTime;

public class FinanceScheduleRegression {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        var saturday=ZonedDateTime.parse("2026-10-17T17:00:00-04:00[America/New_York]");
        var monday=ZonedDateTime.parse("2026-10-19T10:00:00-04:00[America/New_York]");
        check(FinanceSchedulePolicy.jobAt(saturday)==WEEKEND_PLAN);
        check(FinanceSchedulePolicy.jobAt(monday)==WEEKDAY_NEWS);
        check(FinanceSchedulePolicy.jobAt(monday.withHour(23))==null);
        check(!FinanceSchedulePolicy.due(monday,WEEKEND_PLAN,0,0,0));
        check(FinanceSchedulePolicy.due(saturday,WEEKEND_PLAN,0,0,0));
        long now=saturday.toInstant().toEpochMilli();
        check(!FinanceSchedulePolicy.due(saturday,WEEKEND_PLAN,now-3600000,0,0));
        check(!FinanceSchedulePolicy.due(saturday,WEEKEND_PLAN,0,now-14*60000,1));
        check(FinanceSchedulePolicy.due(saturday,WEEKEND_PLAN,0,now-16*60000,1));
        check(!FinanceSchedulePolicy.due(saturday,WEEKEND_PLAN,0,now-16*60000,2));
        check(FinanceSchedulePolicy.retryMinutes(99)==240);
        check(FinanceSchedulePolicy.due(saturday.plusDays(1),WEEKEND_PLAN,now,0,0));
        long mon=monday.toInstant().toEpochMilli();
        check(!FinanceSchedulePolicy.due(monday,WEEKDAY_NEWS,mon-59*60000,0,0));
        check(FinanceSchedulePolicy.due(monday,WEEKDAY_NEWS,mon-61*60000,0,0));
        System.out.println("Finance weekday/weekend schedule and retry regressions passed.");
    }
}
