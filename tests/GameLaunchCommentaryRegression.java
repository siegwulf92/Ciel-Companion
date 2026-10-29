package com.cielcompanion.service;

import java.util.List;

public class GameLaunchCommentaryRegression {
    public static void main(String[] args) {
        GameProgress.Context first = new GameProgress.Context(0, 0, List.of(), List.of());
        String finalMix = GameLaunchCommentary.selectLine("Kingdom Hearts Final Mix", null, first).line();
        if (!finalMix.contains("Sora") || !finalMix.contains("Keyblade"))
            throw new AssertionError("Final Mix fallback lacks a game reference");
        GameLaunchCommentary.Selection generic = GameLaunchCommentary.selectLine("Kingdom Hearts Final Mix",
                "[Observing] Master, Kingdom Hearts Final Mix is ready.", first);
        if (!generic.fromModel())
            throw new AssertionError("An exact-title model line should remain eligible");
        String specific = GameLaunchCommentary.selectLine("Kingdom Hearts Final Mix",
                "[Curious] Master, may Sora's Keyblade serve you well.", first).line();
        if (!specific.contains("Sora's Keyblade"))
            throw new AssertionError("A grounded model line should be kept");
        GameProgress.Context chainHistory = new GameProgress.Context(0, 0,
                List.of("Kingdom Hearts Final Mix"), List.of());
        String chain = GameLaunchCommentary.selectLine("Kingdom Hearts Re:Chain of Memories", null, chainHistory).line();
        if (!chain.contains("Final Mix") || !chain.contains("Castle Oblivion"))
            throw new AssertionError("Chain of Memories fallback lacks game context");
        GameProgress.Context repeat = new GameProgress.Context(1, 600,
                List.of(), List.of(finalMix));
        String varied = GameLaunchCommentary.selectLine("Kingdom Hearts Final Mix", null, repeat).line();
        if (varied.equals(finalMix)) throw new AssertionError("Recent launch line was repeated");
    }
}
