package com.cielcompanion.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LoreOrganizationRegression {
    public static void main(String[] args) throws Exception {
        Path review = Path.of("target", "lore-organization-regression").toAbsolutePath();
        Files.createDirectories(review);
        Field field = VaultService.class.getDeclaredField("loreReviewDir");
        field.setAccessible(true);
        field.set(null, review);

        JsonObject result = new JsonObject();
        result.addProperty("cleaned_text", "Rimuru used Uriel: Ultimate Defense.");
        JsonArray facts = new JsonArray();
        JsonObject character = new JsonObject();
        character.addProperty("category", "Characters");
        character.addProperty("entity", "Rimuru");
        character.addProperty("fact", "Rimuru used a defensive skill.");
        character.addProperty("evidence", "Rimuru used Uriel");
        facts.add(character);
        JsonObject skill = new JsonObject();
        skill.addProperty("category", "Skills");
        skill.addProperty("entity", "Uriel: Ultimate Defense");
        skill.addProperty("skill_type", "Ultimate Skills");
        skill.addProperty("fact", "Uriel provided defense.");
        skill.addProperty("evidence", "Uriel: Ultimate Defense");
        facts.add(skill);
        result.add("lore_facts", facts);

        Method save = VaultService.class.getDeclaredMethod("saveLoreFactCandidates",
                JsonObject.class, String.class, int.class, String.class);
        save.setAccessible(true);
        save.invoke(null, result, "LN Volume 14", 2, "Rimuru used Uriel: Ultimate Defense.");

        Path rimuru = review.resolve("LN 14/Characters/Rimuru.md");
        Path uriel = review.resolve("LN 14/Skills/Ultimate Skills/Uriel - Ultimate Defense.md");
        if (!Files.isRegularFile(rimuru) || !Files.isRegularFile(uriel))
            throw new AssertionError("Readable volume/category/entity hierarchy was not produced");
        if (!Files.readString(uriel).contains("Raw source: [[../../../../../Raw/LN Volume 14]]"))
            throw new AssertionError("Leaf note does not link directly to its raw LN source");
        System.out.println("Readable one-hop lore organization passed.");
    }
}
