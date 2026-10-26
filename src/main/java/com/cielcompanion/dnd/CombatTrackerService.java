package com.cielcompanion.dnd;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.ContextBuilder;
import com.cielcompanion.service.CielTriggerEngine;
import com.cielcompanion.service.SpeechService;
import com.cielcompanion.service.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CombatTrackerService {
    private final List<Combatant> combatants = new ArrayList<>();
    private int currentTurnIndex = 0;
    private boolean combatActive = false;
    private Path lastLiveStateFile;
    private long lastLiveStateModified = Long.MIN_VALUE;
    private long lastLiveStateDiscovery;
    private final Set<String> liveManagedNames = new HashSet<>();
    private static final long LIVE_STATE_REDISCOVERY_MS = 5 * 60 * 1000L;

    private static final Pattern HP_LINE = Pattern.compile("(?i)^\\s*[-*]?\\s*(.+?)\\s*(?:[:\\-]|\\bis\\b|\\bat\\b|\\bhas\\b)\\s*(\\d+)\\s*(?:/\\s*(\\d+))?\\s*hp\\b.*$");
    private static final Pattern TABLE_HP_LINE = Pattern.compile("(?i)^\\s*\\|\\s*([^|]+?)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*/\\s*(\\d+)\\s*(?:hp)?\\s*\\|.*$");
    private static final Pattern INITIATIVE_LINE = Pattern.compile("(?i)^\\s*[-*]?\\s*(.+?)\\s+(?:initiative|init)\\s*(?:[:=]|is)?\\s*(\\d+).*$");
    private static final Pattern TURN_LINE = Pattern.compile("(?i)^\\s*(?:current\\s+turn|active\\s+turn|turn)\\s*[:=]\\s*(.+?)\\s*$");

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
        addCombatant(name, hp, initiative, isPc, "");
    }

    public synchronized void addCombatant(String name, int hp, int initiative, boolean isPc, String role) {
        combatants.add(new Combatant(name, hp, initiative, isPc, role));
        sortInitiative();
        SpeechService.speakPreformatted("Registered " + name + ".");
    }

    public synchronized void applyDamage(String name, int amount) {
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
                SpeechService.speakPreformatted(c.getName() + " remains " + visibleCondition(c) + ".");
            }
        }, () -> SpeechService.speak("Combatant not found."));
    }

    private void triggerWorldVoiceAnalysis(String prompt) {
        // NEW: Pass the prompt to ContextBuilder
        String context = ContextBuilder.buildActiveContext(null, prompt);
        AIEngine.chatFast(prompt, context, null);
    }

    public void nextTurn() {
        if (combatants.isEmpty()) return;
        for (int checked = 0; checked < combatants.size(); checked++) {
            currentTurnIndex = (currentTurnIndex + 1) % combatants.size();
            Combatant active = combatants.get(currentTurnIndex);
            if (!(!active.isPc() && active.isDown())) {
                SpeechService.speakPreformatted("It is now " + active.getName() + "'s turn.");
                return;
            }
        }
    }

    /**
     * Refreshes combat state from a deliberately small, human-editable tracker
     * note. Exact HP remains internal; player-facing observer context exposes
     * only visible conditions such as healthy, bloodied, or down.
     */
    public synchronized void refreshFromLiveNotes(String campaignPath) {
        if (campaignPath == null || campaignPath.isBlank()) return;
        Path root = Paths.get(campaignPath);
        Path file = lastLiveStateFile;
        long now = System.currentTimeMillis();
        if (file == null || !Files.isRegularFile(file)
                || now - lastLiveStateDiscovery >= LIVE_STATE_REDISCOVERY_MS) {
            file = findLiveStateFile(root);
            lastLiveStateDiscovery = now;
        }
        if (file == null) return;
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (file.equals(lastLiveStateFile) && modified == lastLiveStateModified) return;
            String content = Files.readString(file, StandardCharsets.UTF_8);
            combatants.removeIf(c -> liveManagedNames.contains(c.getName().toLowerCase(Locale.ROOT)));
            liveManagedNames.clear();
            for (String line : content.split("\\R")) parseLiveStateLine(line);
            lastLiveStateFile = file;
            lastLiveStateModified = modified;
        } catch (IOException e) {
            System.err.println("Ciel D&D: live tracker refresh failed: " + e.getMessage());
        }
    }

    private Path findLiveStateFile(Path root) {
        String configured = Settings.getDndLiveStatePath();
        if (configured != null && !configured.isBlank()) {
            Path configuredPath = Paths.get(configured);
            if (!configuredPath.isAbsolute()) configuredPath = root.resolve(configuredPath);
            if (Files.isRegularFile(configuredPath)) return configuredPath.normalize();
        }
        String[] preferred = {"Session Tracker.md", "Session_Tracker.md", "Combat Tracker.md", "Initiative Tracker.md",
                "Global Party Tracker.md", "Party Progress Tracker.md"};
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().toLowerCase(Locale.ROOT).contains("99 utilities"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return Arrays.stream(preferred).anyMatch(name::equalsIgnoreCase);
                    })
                    .max(Comparator.comparingLong(path -> {
                        try { return Files.getLastModifiedTime(path).toMillis(); }
                        catch (IOException ignored) { return Long.MIN_VALUE; }
                    })).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void parseLiveStateLine(String line) {
        if (line == null || line.isBlank()) return;
        Matcher table = TABLE_HP_LINE.matcher(line);
        if (table.matches()) {
            upsertLiveCombatant(cleanTrackerName(table.group(1)), Integer.parseInt(table.group(3)),
                    Integer.parseInt(table.group(4)), "");
            findCombatant(cleanTrackerName(table.group(1))).ifPresent(c -> c.setInitiative(Integer.parseInt(table.group(2))));
            sortInitiative();
            return;
        }
        Matcher hp = HP_LINE.matcher(line);
        if (hp.matches()) {
            String rawName = hp.group(1);
            String name = cleanTrackerName(rawName);
            String role = extractRole(rawName);
            if (!name.isBlank()) {
                int current = Integer.parseInt(hp.group(2));
                int max = hp.group(3) == null ? Math.max(current, 1) : Integer.parseInt(hp.group(3));
                upsertLiveCombatant(name, current, max, role);
            }
        }
        Matcher initiative = INITIATIVE_LINE.matcher(line);
        if (initiative.matches()) {
            findCombatant(cleanTrackerName(initiative.group(1))).ifPresent(c -> c.setInitiative(Integer.parseInt(initiative.group(2))));
            sortInitiative();
        }
        Matcher turn = TURN_LINE.matcher(line);
        if (turn.matches()) {
            String active = cleanTrackerName(turn.group(1));
            for (int i = 0; i < combatants.size(); i++) {
                if (combatants.get(i).getName().equalsIgnoreCase(active)) {
                    currentTurnIndex = i;
                    combatActive = true;
                    break;
                }
            }
        }
    }

    private void upsertLiveCombatant(String name, int current, int max, String role) {
        if (name == null || name.isBlank()) return;
        liveManagedNames.add(name.toLowerCase(Locale.ROOT));
        Combatant combatant = findCombatant(name).orElse(null);
        if (combatant == null) {
            combatants.add(new Combatant(name, Math.max(max, current), 0, !looksLikeNpc(name), role));
        } else {
            combatant.setCurrentHp(current);
            if (!role.isBlank()) combatant.setRole(role);
        }
    }

    private String visibleCondition(Combatant combatant) {
        if (combatant.isDown()) return "down";
        if (combatant.getCurrentHp() * 2 <= combatant.getMaxHp()) return "bloodied";
        return "standing";
    }

    private String cleanTrackerName(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("^[#>*\\-\\s]+|\\s+$", "").replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
    }

    private String extractRole(String raw) {
        if (raw == null) return "";
        Matcher role = Pattern.compile("\\(([^)]*)\\)").matcher(raw);
        if (!role.find()) return "";
        String value = role.group(1).trim();
        if (value.equalsIgnoreCase("pc") || value.equalsIgnoreCase("npc")
                || value.equalsIgnoreCase("player") || value.equalsIgnoreCase("enemy")) return "";
        return value;
    }

    private boolean looksLikeNpc(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("goblin") || lower.contains("orc") || lower.contains("bandit")
                || lower.contains("dragon") || lower.contains("enemy") || lower.contains("monster");
    }

    /** Player-safe state for the live observer; never includes exact HP. */
    public synchronized String playerSafeState() {
        if (combatants.isEmpty()) return "No tracked combatants are currently available.";
        StringBuilder state = new StringBuilder("Combat active: ").append(combatActive).append(". ");
        Combatant active = combatants.get(Math.min(currentTurnIndex, combatants.size() - 1));
        state.append("Current turn: ").append(active.getName());
        if (!active.getRole().isBlank()) state.append(" (").append(active.getRole()).append(")");
        state.append(". Visible conditions: ");
        for (int i = 0; i < combatants.size(); i++) {
            Combatant c = combatants.get(i);
            if (i > 0) state.append(", ");
            state.append(c.getName()).append("=");
            if (c.isDown()) state.append("down");
            else if (c.getCurrentHp() * 2 <= c.getMaxHp()) state.append("bloodied");
            else state.append("not bloodied");
            if (!c.getRole().isBlank()) state.append(" (").append(c.getRole()).append(")");
        }
        return state.toString();
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
