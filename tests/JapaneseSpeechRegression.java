import com.cielcompanion.ai.AIEngine;

public class JapaneseSpeechRegression {
    public static void main(String[] args) throws Exception {
        var method = AIEngine.class.getDeclaredMethod("localKatakanaFallback", String.class);
        method.setAccessible(true);
        String nativeText = "テイラー様、大好きだよ。よく頑張ったね。";
        String actual = (String) method.invoke(null, nativeText);
        if (!nativeText.equals(actual)) throw new AssertionError("Native Japanese changed: " + actual);
        String mixed = (String) method.invoke(null, "Taylor様、大好きだよ。");
        if (!mixed.contains("テイラー") || !mixed.contains("大好きだよ")) throw new AssertionError("Name pronunciation or Japanese lost: " + mixed);
        System.out.println("Japanese speech preservation and proper-name pronunciation passed.");
        System.exit(0); // AIEngine owns background executors in the application.
    }
}
