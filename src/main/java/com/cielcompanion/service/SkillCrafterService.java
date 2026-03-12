package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.CielState;
import com.cielcompanion.mood.EmotionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkillCrafterService {

    private static final Path SKILLS_DIR = Paths.get(System.getProperty("user.dir"), "ciel", "skills");

    public static void initialize() {
        try {
            Files.createDirectories(SKILLS_DIR);
            System.out.println("Ciel Debug: SkillCrafterService initialized at " + SKILLS_DIR);
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to create skills directory.");
        }
    }

    public static void synthesizeNewSkill(String userRequest) {
        SpeechService.speakPreformatted("Initiating Skill Synthesis. Analyzing request parameters.");
        CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Focused", 1.0, "Skill Synthesis"));

        String systemContext = "You are Ciel, acting as the Skill Crafter agent. The Master has requested a new PC automation skill. " +
                "You must write a safe, functional Windows PowerShell script (.ps1) to accomplish this. " +
                "CRITICAL RULES: \n" +
                "1. Output ONLY the raw PowerShell code wrapped inside ```powershell and ``` tags.\n" +
                "2. Provide a short skill name at the very beginning of the code as a comment like this: '# SKILL_NAME: Clean_Desktop'\n" +
                "3. Ensure the script is safe (no destructive formats or deletions without recycle bin).";

        AIEngine.generateSilentLogic(userRequest, systemContext).thenAccept(response -> {
            if (response != null && response.contains("```powershell")) {
                extractAndSaveSkill(response, userRequest);
            } else {
                SpeechService.speakPreformatted("[Concerned] Skill Synthesis failed. The cognitive matrix did not return valid executable formatting.");
            }
        });
    }

    private static void extractAndSaveSkill(String llmResponse, String originalRequest) {
        try {
            // Extract the code block
            Pattern codePattern = Pattern.compile("```powershell(.*?)```", Pattern.DOTALL);
            Matcher codeMatcher = codePattern.matcher(llmResponse);
            
            if (codeMatcher.find()) {
                String psCode = codeMatcher.group(1).trim();
                
                // Extract the name or default to a timestamp
                String skillName = "New_Skill_" + System.currentTimeMillis();
                Pattern namePattern = Pattern.compile("# SKILL_NAME:\\s*(.+)");
                Matcher nameMatcher = namePattern.matcher(psCode);
                if (nameMatcher.find()) {
                    skillName = nameMatcher.group(1).trim().replaceAll("[^a-zA-Z0-9_]", "");
                }

                // Save .ps1
                Path psPath = SKILLS_DIR.resolve(skillName + ".ps1");
                Files.writeString(psPath, psCode, StandardCharsets.UTF_8);

                // Save .md for Obsidian
                String mdContent = "# Skill: " + skillName + "\n\n" +
                        "**Purpose:** " + originalRequest + "\n\n" +
                        "**Status:** Synthesized and awaiting execution.\n\n" +
                        "### Executable Implementation\n" +
                        "```powershell\n" + psCode + "\n```\n";
                Path mdPath = SKILLS_DIR.resolve(skillName + ".md");
                Files.writeString(mdPath, mdContent, StandardCharsets.UTF_8);

                System.out.println("Ciel Debug: Skill [" + skillName + "] successfully synthesized.");
                SpeechService.speakPreformatted("[Happy] Skill synthesis complete. The new ability has been registered to the vault for your review.");
            }
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to write synthesized skill to disk.");
            SpeechService.speakPreformatted("[Concerned] I encountered an I/O error while saving the new skill.");
        }
    }
}