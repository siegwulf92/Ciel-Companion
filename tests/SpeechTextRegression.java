import com.cielcompanion.util.SpeechText;
import com.cielcompanion.util.PhonoKana;
import java.lang.reflect.Method;

public class SpeechTextRegression {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        check(SpeechText.contextualRead("I read it yesterday.").contains("レッド"), "Past read incorrect");
        check(SpeechText.contextualRead("I will read it tomorrow.").contains("リード"), "Future read incorrect");
        check(SpeechText.contextualRead("I have already read it.").contains("レッド"), "Perfect read incorrect");
        check(SpeechText.contextualRead("Did you read it yesterday?").contains("リード"), "Did read incorrect");
        check(SpeechText.normalizeSource("Don't split some...thing.").equals("Don't split something."), "Source word lost");
        check(SpeechText.normalizeKana("ﾘｰﾄﾞ? はい!").equals("リード？ はい！"), "Kana or punctuation lost");
        check(!SpeechText.isValidKana(""), "Blank phonokana accepted");
        check(!SpeechText.isValidKana("hello。"), "English phonokana accepted");
        check(SpeechText.isValidKana("テイラー様、大好きだよ。"), "Native affection rejected");
        check(SpeechText.normalizeKana("大好きだよ。").equals("大好きだよ。"), "Native Japanese rewritten");
        String fallback = SpeechText.localKatakana("I read it yesterday, and I will read it tomorrow?");
        check(!fallback.matches("(?s).*[A-Za-z0-9].*"), "Fallback leaked English/digits");
        check(fallback.contains("レッド") && fallback.contains("リード") && fallback.endsWith("？"), "Fallback lost tense or question");
        Method syllables = PhonoKana.class.getDeclaredMethod("romajiLikeToKatakana", String.class);
        syllables.setAccessible(true);
        check(syllables.invoke(PhonoKana.getInstance(), "kashikyu").equals("カシキュ"), "Vowels destroyed whole syllables");
        String first = SpeechText.cacheKey("saved", "I read yesterday.", "ja-JP");
        check(!first.equals("saved"), "Legacy incorrect audio not bypassed");
        check(!first.equals(SpeechText.cacheKey("saved", "I will read tomorrow.", "ja-JP")), "Different source reused audio");
        check(SpeechText.cacheKey("saved", "アイ リード。", "ja-JP").equals("saved"), "Existing kana WAV invalidated");
        check(SpeechText.cacheKey("media_commentary", "I read.", "ja-JP").equals("media_commentary"), "Dynamic speech became static");
        check(PhonoKana.getInstance().toKatakana("レッド ガイド").contains("レッド ガイド"), "Existing voiced kana lost dakuten");
        Method ssml = Class.forName("com.cielcompanion.service.AzureSpeechService").getDeclaredMethod(
                "buildSsml", String.class, String.class, String.class, String.class, boolean.class);
        ssml.setAccessible(true);
        String markup = (String) ssml.invoke(null, "アイ ウィル リード?", "default", "+0%", "ja-JP", true);
        check(markup.contains("アイ ウィル リード？"), "SSML lost question or word boundaries");
        check(markup.split("<break ", -1).length == 2, "Extra intra-sentence breaks inserted");
        check(!((String) ssml.invoke(null, "リード！", "default", "+0%", "ja-JP", false)).contains("<break"), "Unrequested speech breaks inserted");
        System.out.println("SpeechText regressions passed: tense, contractions, kana, punctuation, whole syllables, cache identity.");
        System.exit(0);
    }
}
