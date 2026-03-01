package com.cielcompanion.dnd;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.ContextBuilder;
import com.cielcompanion.service.CielTriggerEngine;
import com.cielcompanion.service.SpeechService;
import java.util.*;

public class CombatTrackerService {
    private final List<Combatant> combatants = new ArrayList<>();
    private int currentTurnIndex = 0;
    private boolean combatActive = false;

    public void startCombat() {
        combatActive = true;
        combatants.clear();
        currentTurnIndex = 0;
        CielTriggerEngine.announce("Combat protocols initiated. Calculating threat levels.", CielTriggerEngine.Urgency.URGENT);
    }

    public void endCombat() {
        combatActive = false;
        combatants.clear();
        CielTriggerEngine.announce("Combat concluded. Returning to standard observation mode.", CielTriggerEngine.Urgency.NORMAL);
    }

    public void addCombatant(String name, int hp, int initiative, boolean isPc) {
        combatants.add(new Combatant(name, hp, initiative, isPc));
        sortInitiative();
        SpeechService.speakPreformatted("Registered " + name + ".");
    }

    public void applyDamage(String name, int amount) {
        findCombatant(name).ifPresentOrElse(c -> {
            c.takeDamage(amount);
            
            // NEW: Analytical Appraisal / World Voice Triggers
            // Automatically prompts Gemma to generate tactical advice and visceral descriptions when HP thresholds are crossed.
            if (c.isDown()) {
                String prompt = "SYSTEM EVENT: Combatant '" + c.getName() + "' (Is Player: " + c.isPc() + ") has just been killed or destroyed. Their HP is 0. " +
                                "As the World Voice, vividly describe their visceral defeat or death in 1 sentence. Then, provide 1 sentence of urgent tactical direction for the remaining party.";
                triggerWorldVoiceAnalysis(prompt);
            } else if (c.checkCritical()) {
                String prompt = "SYSTEM EVENT: Combatant '" + c.getName() + "' (Is Player: " + c.isPc() + ") is critically wounded (HP under 25%). " +
                                "As the World Voice, vividly describe their severe physical wounds (e.g., deep cuts, heavy bleeding) in 1 sentence. Then, suggest 1 immediate tactical action to save them or exploit the weakness.";
                triggerWorldVoiceAnalysis(prompt);
            } else if (c.checkBloodied()) {
                String prompt = "SYSTEM EVENT: Combatant '" + c.getName() + "' (Is Player: " + c.isPc() + ") is bloodied (HP under 50%). " +
                                "As the World Voice, describe their visible fatigue and injuries in 1 sentence. Then, suggest an offensive push or defensive shift.";
                triggerWorldVoiceAnalysis(prompt);
            } else {
                SpeechService.speakPreformatted(c.getName() + " HP: " + c.getCurrentHp());
            }
        }, () -> SpeechService.speak("Combatant not found."));
    }

    private void triggerWorldVoiceAnalysis(String prompt) {
        // Fetch the standard World Voice persona rules
        String context = ContextBuilder.buildActiveContext(null);
        // Feed the battle prompt directly to the fast chat LLM (Gemma) for instant tactical advice
        AIEngine.chatFast(prompt, context, null);
    }

    public void nextTurn() {
        if (combatants.isEmpty()) return;
        currentTurnIndex = (currentTurnIndex + 1) % combatants.size();
        Combatant active = combatants.get(currentTurnIndex);
        
        if (!active.isPc() && active.isDown()) {
            nextTurn();
            return;
        }

        SpeechService.speakPreformatted("It is now " + active.getName() + "'s turn.");
    }

    private void sortInitiative() {
        combatants.sort(Comparator.comparingInt(Combatant::getInitiative).reversed());
    }

    private Optional<Combatant> findCombatant(String name) {
        return combatants.stream()
            .filter(c -> c.getName().equalsIgnoreCase(name))
            .findFirst();
    }
}