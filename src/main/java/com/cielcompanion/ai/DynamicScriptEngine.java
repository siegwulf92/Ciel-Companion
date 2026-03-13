package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.SpeechService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DynamicScriptEngine {

    private static final Set<String> BANNED_KEYWORDS = Set.of(
        "remove-item", "rm ", "del ", "erase ", "rd ", "rmdir ", 
        "format ", "reg add", "reg delete", "netsh", "stop-process", "kill ",
        "invoke-webrequest", "iwr ", "wget ", "curl "
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
            "CRITICAL: If the task implies a variable (like volume level), your script MUST accept arguments using a standard `param(...)` block. " +
            "You MUST output ONLY a valid JSON object. Do not use Markdown blocks (no ```json). " +
            "JSON Format: { \"skill_name\": \"name_with_underscores\", \"script\": \"the raw powershell code\", \"args_for_this_run\": \"any inferred arguments to pass right now\" }.";
            
        attemptGenerationAndExecution(userRequest, systemContext, 0, onComplete);
    }

    private static void attemptGenerationAndExecution(String prompt, String systemContext, int attemptNum, Runnable onComplete) {
        AIEngine.generateSilentLogic(prompt, systemContext).thenAccept(response -> {
            if (response == null || response.isBlank()) {
                speakStatus("[Annoyed] スクリプト ジェネレーション フェイルド。");
                if (onComplete != null) onComplete.run();
                return;
            }
            
            try {
                String cleanJson = response.replace("```json", "").replace("```", "").trim();
                JsonObject jsonResponse = JsonParser.parseString(cleanJson).getAsJsonObject();
                
                String skillName = jsonResponse.get("skill_name").getAsString();
                String script = jsonResponse.get("script").getAsString();
                String argsForRun = jsonResponse.has("args_for_this_run") ? jsonResponse.get("args_for_this_run").getAsString() : "";
                
                String lowerScript = script.toLowerCase();
                for (String banned : BANNED_KEYWORDS) {
                    if (lowerScript.contains(banned)) {
                        speakStatus("[Pain] セキュリティ ヴァイオレーション ディテクテッド。エクセキューション アボーテッド。");
                        if (onComplete != null) onComplete.run();
                        return;
                    }
                }
                
                if (attemptNum == 0) speakStatus("[Observing] エクセキューティング ダイナミック シーケンス。");
                else speakStatus("[Focused] アプライイング コレクションズ。リアテンプティング エクセキューション。"); 

                // SECURITY UPGRADE: Encode the script directly into a Base64 string for fileless execution
                String scriptToExecute = script;
                if (!argsForRun.isBlank()) {
                     scriptToExecute = script + "\n" + argsForRun;
                }
                String base64Command = Base64.getEncoder().encodeToString(scriptToExecute.getBytes(StandardCharsets.UTF_16LE));

                List<String> command = new ArrayList<>();
                command.add("powershell.exe");
                command.add("-ExecutionPolicy");
                command.add("Bypass");
                command.add("-EncodedCommand");
                command.add(base64Command);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true); 
                
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();
                
                boolean hasError = exitCode != 0 || output.toLowerCase().contains("exception") || output.toLowerCase().contains("error");
                
                if (!hasError) {
                    // Uses the new encrypted SkillManager logic
                    SkillManager.saveSkill(skillName, script);
                    speakStatus("[Happy] チャント アナルメント サクセスフル。スキル アシミレイテッド。");
                    if (onComplete != null) onComplete.run();
                } else {
                    if (attemptNum < 2) {
                        String correctionPrompt = "The script failed with this output/error:\n" + output + "\nRewrite the script to fix this error. Ensure you still output strictly JSON.";
                        attemptGenerationAndExecution(correctionPrompt, systemContext, attemptNum + 1, onComplete);
                    } else {
                        speakStatus("[Annoyed] エクセキューション エンカウンタード クリティカル アノマリー。スクリプト アボーテッド。");
                        if (onComplete != null) onComplete.run();
                    }
                }
                
            } catch (Exception e) {
                speakStatus("[Annoyed] ジェイソン パーシング オア エクセキューション フェイルド。");
                if (onComplete != null) onComplete.run();
            }
        });
    }
}