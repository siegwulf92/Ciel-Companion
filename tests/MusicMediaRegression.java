import java.lang.reflect.Method;

public class MusicMediaRegression {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Class<?> tracker = Class.forName("com.cielcompanion.service.HabitTrackerService");
        Method detector = tracker.getDeclaredMethod("isMusicMedia", String.class, String.class, String.class);
        detector.setAccessible(true);
        Method shortForm = tracker.getDeclaredMethod("isShortFormMedia", String.class, String.class, String.class);
        shortForm.setAccessible(true);
        check((Boolean) detector.invoke(null, "Tokyo Drift - Teriyaki Boyz [ MUSIC VIDEO ] HD", "", "FILM"),
                "music-video title was not classified as music");
        check((Boolean) detector.invoke(null, "Official Audio - Example", "", "VIDEO"),
                "official audio title was not classified as music");
        check((Boolean) detector.invoke(null, "A regular feature film", "MEDIA_KIND: MUSIC\n", "FILM"),
                "explicit music metadata was not classified as music");
        check(!(Boolean) detector.invoke(null, "The Apothecary Diaries - Episode 4", "MEDIA_KIND: FILM\n", "FILM"),
                "ordinary episode was incorrectly classified as music");
        check((Boolean) shortForm.invoke(null, "A creator short", "MEDIA_KIND: VIDEO\n", "https://www.youtube.com/shorts/abc"),
                "explicit Shorts URL was not classified as short-form media");
        check(!(Boolean) shortForm.invoke(null, "The Apothecary Diaries - Episode 4", "MEDIA_KIND: EPISODE\n", "https://example.test/episode"),
                "ordinary episode was incorrectly classified as short-form media");
        System.out.println("Music media regressions passed.");
    }
}
