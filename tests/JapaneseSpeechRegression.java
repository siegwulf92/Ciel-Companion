import com.cielcompanion.ai.AIEngine;

public class JapaneseSpeechRegression {
    public static void main(String[] args) throws Exception {
        String nativeText = "テイラー様、大好きだよ。よく頑張ったね。";
        String actual = AIEngine.transliterateToKatakanaSync(nativeText);
        if (!nativeText.equals(actual)) throw new AssertionError("Native Japanese changed: " + actual);
        if (!com.cielcompanion.util.SpeechText.isValidKana(nativeText))
            throw new AssertionError("Native affection rejected by speech validation");
        System.out.println("Native Japanese speech preservation and affection validation passed.");
        System.exit(0); // AIEngine owns background executors in the application.
    }
}
