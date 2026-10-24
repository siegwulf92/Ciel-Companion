package com.cielcompanion.service;

public class GameLaunchCommentaryRegression {
    public static void main(String[] args) {
        String finalMix = GameLaunchCommentary.selectLine("Kingdom Hearts Final Mix", null);
        if (!finalMix.contains("Sora") || !finalMix.contains("Keyblade"))
            throw new AssertionError("Final Mix fallback lacks a game reference");
        String generic = GameLaunchCommentary.selectLine("Kingdom Hearts Final Mix",
                "[Observing] Master, Kingdom Hearts Final Mix is ready.");
        if (!generic.equals(finalMix))
            throw new AssertionError("A title-only model line should use the grounded fallback");
        String specific = GameLaunchCommentary.selectLine("Kingdom Hearts Final Mix",
                "[Curious] Master, may Sora's Keyblade serve you well.");
        if (!specific.contains("Sora's Keyblade"))
            throw new AssertionError("A grounded model line should be kept");
        String chain = GameLaunchCommentary.selectLine("Kingdom Hearts Re:Chain of Memories", null);
        if (!chain.contains("Castle Oblivion") || !chain.contains("card"))
            throw new AssertionError("Chain of Memories fallback lacks game context");
    }
}
