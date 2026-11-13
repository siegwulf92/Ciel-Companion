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
        var veteransDay=ZonedDateTime.parse("2026-11-11T12:00:00-05:00[America/New_York]");
        check(FinanceSchedulePolicy.isFullDayHoliday(veteransDay.toLocalDate()));
        check(FinanceSchedulePolicy.briefingAt(veteransDay)==CLOSED_MARKET_PLAN);
        long nov10 = veteransDay.minusDays(1).withHour(18).toInstant().toEpochMilli();
        long nov9 = veteransDay.minusDays(2).withHour(18).toInstant().toEpochMilli();
        check(!FinanceSchedulePolicy.holidayCatchupDue(veteransDay.withHour(12), nov9, 0, 0));
        check(FinanceSchedulePolicy.holidayCatchupDue(veteransDay.withHour(18), nov9, 0, 0));
        check(!FinanceSchedulePolicy.holidayCatchupDue(veteransDay.withHour(18), nov10, 0, 0));
        check(!FinanceSchedulePolicy.due(monday,CLOSED_MARKET_PLAN,0,0,0));
        check(FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,0,0,0));
        long now=saturday.toInstant().toEpochMilli();
        check(!FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,now-3600000,0,0));
        check(!FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,0,now-14*60000,1));
        check(FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,0,now-16*60000,1));
        check(!FinanceSchedulePolicy.due(saturday,CLOSED_MARKET_PLAN,0,now-16*60000,2));
        check(FinanceSchedulePolicy.retryMinutes(99)==240);
        check(FinanceSchedulePolicy.due(saturday.plusDays(1),CLOSED_MARKET_PLAN,now,0,0));
        var preOpen=ZonedDateTime.parse("2026-10-19T02:22:00-04:00[America/New_York]");
        check(!FinanceSchedulePolicy.due(preOpen,DAILY_BRIEFING,0,0,0));
        check(!FinanceSchedulePolicy.due(preOpen,DAILY_BRIEFING,yesterdayOrZero(preOpen),0,0));
        long mon=monday.toInstant().toEpochMilli();
        long yesterday=monday.minusDays(1).withHour(22).toInstant().toEpochMilli();
        check(FinanceSchedulePolicy.due(monday,DAILY_BRIEFING,yesterday,0,0));
        check(FinanceSchedulePolicy.due(monday,DAILY_BRIEFING,monday.withHour(2).toInstant().toEpochMilli(),0,0));
        check(!FinanceSchedulePolicy.due(monday,DAILY_BRIEFING,monday.withHour(9).withMinute(45).toInstant().toEpochMilli(),0,0));
        check(FinanceSchedulePolicy.due(monday,DAILY_BRIEFING,mon-24*60*60000L,0,0));
        System.out.println("Finance weekday/weekend schedule and retry regressions passed.");
    }
    static long yesterdayOrZero(ZonedDateTime day) { return day.minusDays(1).withHour(22).toInstant().toEpochMilli(); }
}
