import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Protect reviewed phonokana lines that bypass the dynamic model pipeline. */
public final class PreformattedDialogueRegression {
    public static void main(String[] args) throws Exception {
        Properties lines = new Properties();
        try (InputStream stream = PreformattedDialogueRegression.class.getClassLoader()
                .getResourceAsStream("ciel_lines_ja.properties")) {
            if (stream == null) throw new AssertionError("ciel_lines_ja.properties missing");
            lines.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        String reacquired = lines.getProperty("return_from_idle.4", "");
        if (!reacquired.contains("リーアクワイアード")) {
            throw new AssertionError("reacquired phonokana regressed: " + reacquired);
        }
        System.out.println("Preformatted dialogue pronunciation regression passed.");
    }
}
