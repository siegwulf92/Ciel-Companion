import com.cielcompanion.util.ProjectPaths;
import java.nio.file.*;

public class PythonRuntimeRegression {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("ciel-python-runtime");
        System.setProperty("ciel.root",root.toString());
        System.clearProperty("ciel.python");
        if(!ProjectPaths.pythonExecutable().equals("python"))throw new AssertionError("System runtime fallback");
        Path nested=root.resolve("OpenJarvis-main/.venv/Scripts/python.exe");
        Files.createDirectories(nested.getParent());Files.createFile(nested);
        if(!ProjectPaths.pythonExecutable().equals(nested.toString()))throw new AssertionError("Nested runtime missing");
        System.setProperty("ciel.python","C:/device runtime/python.exe");
        if(!ProjectPaths.pythonExecutable().equals("C:/device runtime/python.exe"))throw new AssertionError("Device override ignored");
        System.out.println("Shared router/pronunciation Python runtime selection passed.");
    }
}
