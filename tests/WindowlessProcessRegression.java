import com.cielcompanion.util.WindowlessCommand;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class WindowlessProcessRegression {
    public static void main(String[] args) throws Exception {
        WindowlessCommand.Result echo = WindowlessCommand.run(
                List.of("cmd.exe", "/d", "/c", "echo", "ciel-hidden-ok"), 10);
        if (echo.exitCode() != 0)
            throw new AssertionError("Windowless command wrapper failed");
        if (!echo.output().contains("ciel-hidden-ok"))
            throw new AssertionError("Windowless wrapper did not preserve stdout");

        WindowlessCommand.Result stdin = WindowlessCommand.run(
                List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "-"), 10,
                "Write-Output 'ciel-stdin-ok'\n".getBytes(StandardCharsets.UTF_8));
        if (stdin.exitCode() != 0)
            throw new AssertionError("Windowless stdin command failed");
        if (!stdin.output().contains("ciel-stdin-ok"))
            throw new AssertionError("Windowless wrapper did not preserve stdin/stdout");
        System.out.println("Windowless child process transport passed.");
    }
}
