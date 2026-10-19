import java.nio.file.*;
import java.util.Properties;
import java.io.*;

public class LocalTriggerKeyRegression {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("ciel-trigger-regression");
        System.setProperty("ciel.root", root.toString());
        Path file=root.resolve(".local/trigger-keys.properties");
        Files.createDirectories(file.getParent());
        Properties keys=new Properties();
        String legacy="synthetic-legacy-search-key-001";
        keys.setProperty("command","synthetic-legacy-command-key-00000001");
        keys.setProperty("search",legacy);
        try(var output=Files.newOutputStream(file)){keys.store(output,"test fixture");}
        Class<?> app=Class.forName("com.cielcompanion.CielCompanion");
        var load=app.getDeclaredMethod("localTriggerKey",String.class);
        load.setAccessible(true);
        if(!legacy.equals(load.invoke(null,"search")))throw new AssertionError("Legacy key changed");
        String generated=(String)load.invoke(null,"new-test-key");
        if(!generated.matches("[0-9a-f]{64}"))throw new AssertionError("New key strength");
        keys.setProperty("search","short");
        try(var output=Files.newOutputStream(file)){keys.store(output,"test fixture");}
        try {load.invoke(null,"search");throw new AssertionError("Short key accepted");}
        catch(java.lang.reflect.InvocationTargetException expected){
            if(!(expected.getCause() instanceof IllegalStateException))throw expected;
        }
        System.out.println("Local trigger migration regression passed.");
        System.exit(0);
    }
}
