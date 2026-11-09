import com.cielcompanion.ui.CielGui;

/** Verifies the orb contracts at silence and expands materially at speech peaks. */
public class SpeechPulseRegression {
    public static void main(String[] args) {
        float quiet = CielGui.calculateSpeechPulse(0f, Long.MAX_VALUE, 0.8f);
        float loud = CielGui.calculateSpeechPulse(1f, Long.MAX_VALUE, 0.8f);
        float word = CielGui.calculateSpeechPulse(0f, 0L, 0.8f);
        if (quiet > 0.06f) throw new AssertionError("Quiet baseline is not contracted: " + quiet);
        if (loud < 0.88f) throw new AssertionError("Loud speech does not expand enough: " + loud);
        if (word < 0.90f) throw new AssertionError("Word boundary is not visibly articulated: " + word);
        if (loud - quiet < 0.80f) throw new AssertionError("Speech range is too subtle: " + (loud - quiet));
        System.out.println("Speech pulse regression passed: quiet baseline and loud expansion remain distinct.");
    }
}
