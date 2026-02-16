package com.cielcompanion.dnd;

import com.cielcompanion.service.CielTriggerEngine;
import com.cielcompanion.service.SpeechService;
import java.util.*;
import java.util.stream.Collectors;

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
            
            // Status Checks
            if (c.isDown()) {
                CielTriggerEngine.announce("Target " + c.getName() + " neutralized.", CielTriggerEngine.Urgency.CRITICAL);
            } else if (c.checkCritical()) {
                CielTriggerEngine.announce("Target " + c.getName() + " is critical. Vital signs failing.", CielTriggerEngine.Urgency.URGENT);
            } else if (c.checkBloodied()) {
                CielTriggerEngine.announce("Target " + c.getName() + " is bloodied.", CielTriggerEngine.Urgency.NORMAL);
            } else {
                SpeechService.speakPreformatted(c.getName() + " HP: " + c.getCurrentHp());
            }
        }, () -> SpeechService.speak("Combatant not found."));
    }

    public void nextTurn() {
        if (combatants.isEmpty()) return;
        currentTurnIndex = (currentTurnIndex + 1) % combatants.size();
        Combatant active = combatants.get(currentTurnIndex);
        
        // Skip dead enemies (optional, keeps flow faster)
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