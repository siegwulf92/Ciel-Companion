package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.SpeechService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles "Chant Annulment" by generating and executing secure PowerShell scripts on the fly.
 */
public class DynamicScriptEngine {

    // Strict blacklist to prevent Ciel from accidentally deleting system files or nuking the registry
    private static final Set<String> BANNED_KEYWORDS = Set.of(
        "remove-item", "rm ", "del ", "format ", "reg add", "reg delete", "netsh", "stop-process", "kill "
    );

    // Helper to strip emotion tags and speak Katakana naturally
    private static void speakStatus(String textWithEmotion) {
        String cleanText = textWithEmotion.trim();
        Matcher matcher = Pattern.compile("\\[([a-zA-Z]+)\\]").matcher(cleanText);
        String emotionToTrigger = null;

        while (matcher.find()) {
            emotionToTrigger = matcher.group(1);
        }
        
        cleanText = matcher.replaceAll("").trim();

        if (emotionToTrigger != null && !emotionToTrigger.isBlank()) {
            final String finalEmotion = emotionToTrigger;
            CielState.getEmotionManager().ifPresent(em -> {
                em.triggerEmotion(finalEmotion, 0.8, "Chant Annulment");
            });
        }

        if (!cleanText.isEmpty()) {
            SpeechService.speakPreformatted(cleanText);
        }
    }

    public static void executeChantAnnulment(String userRequest, Runnable onComplete) {
        // "Chant Annulment protocol initiated. Formulating script sequence."
        speakStatus("[Focused] チャント アナルメント プロトコル イニシエイテッド。フォーミュレイティング スクリプト シーケンス。");
        
        String systemContext = "You are a master Windows PowerShell scripter. " +
            "The user will give you a natural language task. Write a safe, efficient PowerShell script to accomplish it. " +
            "CRITICAL: Output ONLY the raw PowerShell code. Do not include markdown blocks (like ```powershell). Do not explain the code. " +
            "The script must execute immediately without requiring user input. " +
            "If the task implies deleting files, use Move-Item to a 'C:\\Temp_Recycle' folder instead to be safe.";
            
        AIEngine.generateSilentLogic(userRequest, systemContext).thenAccept(script -> {
            if (script == null || script.isBlank()) {
                // "Script generation failed. Logic matrix unstable."
                speakStatus("[Annoyed] スクリプト ジェネレーション フェイルド。ロジック マトリックス アンステイブル。");
                if (onComplete != null) onComplete.run();
                return;
            }
            
            // Clean up rogue markdown just in case the LLM ignores instructions
            script = script.replace("```powershell", "").replace("```ps1", "").replace("```", "").trim();
            System.out.println("\n--- CIEL GENERATED SCRIPT ---\n" + script + "\n-----------------------------\n");
            
            // Security Check
            String lowerScript = script.toLowerCase();
            for (String banned : BANNED_KEYWORDS) {
                if (lowerScript.contains(banned)) {
                    // "Security violation detected. Execution aborted."
                    speakStatus("[Pain] セキュリティ ヴァイオレーション ディテクテッド。エクセキューション アボーテッド。");
                    if (onComplete != null) onComplete.run();
                    return;
                }
            }
            
            try {
                Path scriptPath = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "temp_chant.ps1");
                Files.createDirectories(scriptPath.getParent());
                Files.writeString(scriptPath, script);
                
                // "Executing dynamic sequence."
                speakStatus("[Observing] エクセキューティング ダイナミック シーケンス。");
                
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toString());
                pb.redirectErrorStream(true); 
                
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();
                
                System.out.println("Ciel Debug: PowerShell Execution Output:\n" + output);
                
                if (exitCode == 0 && !output.toLowerCase().contains("exception") && !output.toLowerCase().contains("error")) {
                    // "Chant Annulment successful. Task completed."
                    speakStatus("[Happy] チャント アナルメント サクセスフル。タスク コンプリート。");
                } else {
                    // "Execution encountered an anomaly. The script failed."
                    speakStatus("[Annoyed] エクセキューション エンカウンタード アン アノマリー。スクリプト フェイルド。");
                }
                
            } catch (Exception e) {
                // "Execution failed due to a system anomaly."
                speakStatus("[Annoyed] エクセキューション フェイルド デュー トゥ ア システム アノマリー。");
                e.printStackTrace();
            } finally {
                if (onComplete != null) onComplete.run();
            }
        });
    }
}