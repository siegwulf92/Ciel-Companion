package com.cielcompanion.service;

import java.nio.file.Path;

public final class VaultRequestRoutingRegression {
    public static void main(String[] args) {
        String novelText = "\\#transcript\nThe campaign leader proposed an idea to analyze later.";
        if (!VaultService.isLoreRequest(Path.of("LN Volume 6.md"), novelText)) {
            throw new AssertionError("A numbered LN file must use the lore queue.");
        }
        if (!VaultService.isLoreRequest(Path.of("notes.md"), novelText)) {
            throw new AssertionError("An explicit transcript marker must use the lore queue.");
        }
        if (VaultService.isLoreRequest(Path.of("Campaign Review.md"), "#dm-review\nSuggest encounter ideas.")) {
            throw new AssertionError("A real campaign-review request must remain a campaign request.");
        }
        System.out.println("Lore requests take precedence over campaign heuristics.");
    }
}
