package com.cielcompanion.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Redacted product capability policy shared by Java and future Python gates. */
public final class ProductProfileService {
    private ProductProfileService() {}

    public static boolean allows(String capability) {
        String normalized = normalize(capability);
        if (normalized.isEmpty()) return false;
        Set<String> denied = parse(Settings.getProductDeniedCapabilities());
        if (denied.contains("*") || denied.contains(normalized)) return false;
        Set<String> allowed = parse(Settings.getProductCapabilities());
        return "owner".equals(Settings.getProductTier())
                || allowed.contains("*")
                || allowed.contains(normalized);
    }

    /** Safe for capability-status.json and peer health responses. */
    public static JsonObject snapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("policy_version", Settings.getProductPolicyVersion());
        root.addProperty("tier", Settings.getProductTier());
        root.addProperty("capability_policy", Settings.getProductCapabilities().isBlank()
                ? "explicit-allowlist" : Settings.getProductCapabilities());
        JsonArray denied = new JsonArray();
        for (String item : parse(Settings.getProductDeniedCapabilities())) denied.add(item);
        root.add("denied_capabilities", denied);
        root.addProperty("license_material_present", false);
        return root;
    }

    private static Set<String> parse(String csv) {
        Set<String> result = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return result;
        Arrays.stream(csv.split(","))
                .map(ProductProfileService::normalize)
                .filter(item -> !item.isEmpty())
                .forEach(result::add);
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
