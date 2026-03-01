package com.cielcompanion.ai;

import com.cielcompanion.service.SpeechService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Handles "Chant Annulment" by generating and executing secure PowerShell scripts on the fly.
 */
public class DynamicScriptEngine {

    // Strict blacklist to prevent Ciel from accidentally deleting system files or nuking the registry
    private static final Set<String> BANNED_KEYWORDS = Set.of(
        "remove-item", "rm ", "del ", "format ", "reg add", "reg delete", "netsh", "stop-process", "kill "
    );

    public static void executeChantAnnulment(String userRequest, Runnable onComplete) {
        SpeechService.speakPreformatted("[Focused] Chant Annulment protocol initiated. Formulating script sequence.");
        
        String systemContext = "You are a master Windows PowerShell scripter. " +
            "The user will give you a natural language task. Write a safe, efficient PowerShell script to accomplish it. " +
            "CRITICAL: Output ONLY the raw PowerShell code. Do not include markdown blocks (like ```powershell). Do not explain the code. " +
            "If the task implies deleting files, use Move-Item to a 'C:\\Temp_Recycle' folder instead to be safe.";
            
        AIEngine.generateSilentLogic(userRequest, systemContext).thenAccept(script -> {
            if (script == null || script.isBlank()) {
                SpeechService.speakPreformatted("[Annoyed] Script generation failed. Logic matrix unstable.");
                if (onComplete != null) onComplete.run();
                return;
            }
            
            // Clean up rogue markdown just in case the LLM ignores instructions
            script = script.replace("```powershell", "").replace("```ps1", "").replace("```", "").trim();
            
            // Security Check
            String lowerScript = script.toLowerCase();
            for (String banned : BANNED_KEYWORDS) {
                if (lowerScript.contains(banned)) {
                    SpeechService.speakPreformatted("[Pain] Security violation detected. The generated script contained banned operations. Execution aborted.");
                    if (onComplete != null) onComplete.run();
                    return;
                }
            }
            
            try {
                Path scriptPath = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "temp_chant.ps1");
                Files.createDirectories(scriptPath.getParent());
                Files.writeString(scriptPath, script);
                
                SpeechService.speakPreformatted("[Observing] Executing dynamic sequence.");
                
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toString());
                Process process = pb.start();
                process.waitFor();
                
                SpeechService.speakPreformatted("[Happy] Chant Annulment successful. Task completed.");
                
            } catch (Exception e) {
                SpeechService.speakPreformatted("[Annoyed] Execution failed due to a system anomaly.");
                e.printStackTrace();
            } finally {
                if (onComplete != null) onComplete.run();
            }
        });
    }
}