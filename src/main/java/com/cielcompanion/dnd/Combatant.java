package com.cielcompanion.dnd;

public class Combatant {
    private String name;
    private int maxHp;
    private int currentHp;
    private int initiative;
    private boolean isPc; // Player Character vs NPC
    private boolean bloodiedAnnounced = false;
    private boolean criticalAnnounced = false;

    public Combatant(String name, int maxHp, int initiative, boolean isPc) {
        this.name = name;
        this.maxHp = maxHp;
        this.currentHp = maxHp;
        this.initiative = initiative;
        this.isPc = isPc;
    }

    public String getName() { return name; }
    public int getCurrentHp() { return currentHp; }
    public boolean isPc() { return isPc; }
    public int getInitiative() { return initiative; }

    public void heal(int amount) {
        this.currentHp = Math.min(maxHp, currentHp + amount);
        resetThresholds();
    }

    public void takeDamage(int amount) {
        this.currentHp -= amount;
    }

    public boolean checkBloodied() {
        if (!bloodiedAnnounced && currentHp <= (maxHp / 2) && currentHp > 0) {
            bloodiedAnnounced = true;
            return true;
        }
        return false;
    }

    public boolean checkCritical() {
        if (!criticalAnnounced && currentHp <= (maxHp / 4) && currentHp > 0) {
            criticalAnnounced = true;
            return true;
        }
        return false;
    }

    public boolean isDown() {
        return currentHp <= 0;
    }

    private void resetThresholds() {
        if (currentHp > (maxHp / 4)) criticalAnnounced = false;
        if (currentHp > (maxHp / 2)) bloodiedAnnounced = false;
    }
}