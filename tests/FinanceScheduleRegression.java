import com.cielcompanion.service.FinanceSchedulePolicy;
import static com.cielcompanion.service.FinanceSchedulePolicy.Job.*;
import java.time.ZonedDateTime;

public class FinanceScheduleRegression {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        var saturday=ZonedDateTime.parse("2026-10-17T17:00:00-04:00[America/New_York]");
        var monday=ZonedDateTime.parse("2026-10-19T10:00:00-04:00[America/New_York]");
        check(FinanceSchedulePolicy.briefingAt(saturday)==CLOSED_MARKET_PLAN);
        check(FinanceSchedulePolicy.briefingAt(monday)==DAILY_BRIEFING);
        check(FinanceSchedulePolicy.briefingAt(monday.withHour(23))==DAILY_BRIEFING);
        check(!FinanceSchedulePolicy.due(monday,CLOSED_MARKET_PLAN,0,0,0));
        check(FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,0,0,0));
        long now=saturday.toInstant().toEpochMilli();
        check(!FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,now-3600000,0,0));
        check(!FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,0,now-14*60000,1));
        check(FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,0,now-16*60000,1));
        check(!FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,0,now-16*60000,2));
        check(FinanceSchedulePolicy.retryMinutes(99)==240);
        check(!FinanceSchedulePolicy.due(saturday.plusDays(1).withHour(10),CLOSED_MARKET_PLAN,now,0,0));
        check(FinanceSchedulePolicy.due(saturday.plusDays(1).withHour(20),CLOSED_MARKET_PLAN,now,0,0));
        long mon=monday.toInstant().toEpochMilli();
        check(!FinanceSchedulePolicy.due(monday,NEWS_WATCH,mon-59*60000,0,0));
        check(FinanceSchedulePolicy.due(monday,NEWS_WATCH,mon-61*60000,0,0));
        check(FinanceSchedulePolicy.marketClosed(java.time.LocalDate.parse("2026-11-26")));
        check(!FinanceSchedulePolicy.marketClosed(java.time.LocalDate.parse("2026-11-11")));
        check(FinanceSchedulePolicy.briefingAt(ZonedDateTime.parse("2026-12-25T10:00:00-05:00[America/New_York]"))==CLOSED_MARKET_PLAN);
        System.out.println("Finance daily, closed-market, hourly news and retry regressions passed.");
    }
}
