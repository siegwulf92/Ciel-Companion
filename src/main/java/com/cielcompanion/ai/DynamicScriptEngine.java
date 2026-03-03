package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.SpeechService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DynamicScriptEngine {

    private static final Set<String> BANNED_KEYWORDS = Set.of(
        "remove-item", "rm ", "del ", "format ", "reg add", "reg delete", "netsh", "stop-process", "kill "
    );

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
        speakStatus("[Focused] チャント アナルメント プロトコル イニシエイテッド。フォーミュレイティング スクリプト シーケンス。");
        
        String systemContext = "You are a master Windows PowerShell scripter. " +
            "The user will give you a natural language task. Write a safe, efficient PowerShell script to accomplish it. " +
            "CRITICAL: You MUST output ONLY a valid JSON object. Do not use Markdown blocks (no ```json). " +
            "JSON Format: { \"skill_name\": \"a_short_descriptive_name_with_underscores\", \"script\": \"the raw powershell code\" }. " +
            "The script must execute immediately without requiring user input. " +
            "If the task implies deleting files, use Move-Item to a 'C:\\Temp_Recycle' folder instead.";
            
        AIEngine.generateSilentLogic(userRequest, systemContext).thenAccept(response -> {
            if (response == null || response.isBlank()) {
                speakStatus("[Annoyed] スクリプト ジェネレーション フェイルド。ロジック マトリックス アンステイブル。");
                if (onComplete != null) onComplete.run();
                return;
            }
            
            try {
                // Clean up possible markdown tags just in case
                String cleanJson = response.replace("```json", "").replace("```", "").trim();
                JsonObject jsonResponse = JsonParser.parseString(cleanJson).getAsJsonObject();
                
                String skillName = jsonResponse.get("skill_name").getAsString();
                String script = jsonResponse.get("script").getAsString();
                
                System.out.println("\n--- CIEL GENERATED SCRIPT [" + skillName + "] ---\n" + script + "\n-----------------------------\n");
                
                String lowerScript = script.toLowerCase();
                for (String banned : BANNED_KEYWORDS) {
                    if (lowerScript.contains(banned)) {
                        speakStatus("[Pain] セキュリティ ヴァイオレーション ディテクテッド。エクセキューション アボーテッド。");
                        if (onComplete != null) onComplete.run();
                        return;
                    }
                }
                
                Path tempPath = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "temp_chant.ps1");
                Files.writeString(tempPath, script);
                
                speakStatus("[Observing] エクセキューティング ダイナミック シーケンス。");
                
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", tempPath.toString());
                pb.redirectErrorStream(true); 
                
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();
                
                System.out.println("Ciel Debug: PowerShell Execution Output:\n" + output);
                
                if (exitCode == 0 && !output.toLowerCase().contains("exception") && !output.toLowerCase().contains("error")) {
                    // NEW: Assimilate the skill permanently if it executed successfully
                    SkillManager.saveSkill(skillName, script);
                    speakStatus("[Happy] チャント アナルメント サクセスフル。スキル アシミレイテッド。"); // Chant Annulment successful. Skill assimilated.
                } else {
                    speakStatus("[Annoyed] エクセキューション エンカウンタード アン アノマリー。スクリプト フェイルド。");
                }
                
            } catch (Exception e) {
                speakStatus("[Annoyed] ジェイソン パーシング オア エクセキューション フェイルド。");
                e.printStackTrace();
            } finally {
                if (onComplete != null) onComplete.run();
            }
        });
    }
}