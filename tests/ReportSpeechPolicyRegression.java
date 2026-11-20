import com.cielcompanion.service.ReportSpeechPolicy;

public class ReportSpeechPolicyRegression {
    public static void main(String[] args) {
        for (String report : new String[]{"idle_report.20261119.0", "idle_report.none",
                "phonokana-v7-reviewed:idle_report.20261119.3", "phonokana-v8:idle_report.none"}) {
            check(ReportSpeechPolicy.useBreeze(report, true, false), "matching cache must remain usable");
            check(!ReportSpeechPolicy.useBreeze(report, false, false), "report miss must use Azure");
            check(!ReportSpeechPolicy.maySynthesize(report, false), "prefetch/final fallback cannot spend");
            check(ReportSpeechPolicy.useBreeze(report, false, true), "explicit setting can enable generation");
        }
        for (String generic : new String[]{null, "startup.0", "return_idle", "media_commentary",
                "phonokana-v7-reviewed:startup.0", "not_idle_report.1", "idle_reporter.0"}) {
            check(ReportSpeechPolicy.useBreeze(generic, false, false), "generic dialogue unchanged");
        }
        java.util.List<String> queued = new java.util.ArrayList<>();
        java.util.List<String> failures = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CompletableFuture<String> first = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<String> second = new java.util.concurrent.CompletableFuture<>();
        ReportSpeechPolicy.routeRequestedReport(first, "idle_report.requested.first",
                (text, key) -> queued.add(key + ":" + text), failures::add, completed::incrementAndGet);
        ReportSpeechPolicy.routeRequestedReport(second, "idle_report.requested.second",
                (text, key) -> queued.add(key + ":" + text), failures::add, completed::incrementAndGet);
        second.complete("Second report");
        first.complete("First report");
        check(queued.equals(java.util.List.of("idle_report.requested.second:Second report",
                "idle_report.requested.first:First report")), "async completion must preserve each report key");
        ReportSpeechPolicy.routeRequestedReport(java.util.concurrent.CompletableFuture.completedFuture(" "),
                "idle_report.requested.empty", (text, key) -> queued.add(text), failures::add, completed::incrementAndGet);
        check(queued.size() == 2 && failures.equals(java.util.List.of("empty_response"))
                && completed.get() == 3, "empty generation must fail clearly and release command state");
        System.out.println("Report speech policy regression passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
