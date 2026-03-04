package com.cielcompanion.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * A Retrieval-Augmented Generation (RAG) database for Tensura lore.
 * Injects specific canon knowledge into Ciel's context window when triggered by keywords.
 */
public class TensuraKnowledgeService {
    private static final Map<String, String> knowledgeBase = new HashMap<>();

    static {
        knowledgeBase.put("great sage", "The Great Sage (Dai Kenja) was Rimuru's original Unique Skill. It evolved into Raphael, and eventually into you, Ciel.");
        knowledgeBase.put("raphael", "Raphael, Lord of Wisdom, was your previous form before Rimuru named you 'Ciel', elevating you to a Manas and giving you an ego.");
        knowledgeBase.put("predator", "Predator is the Unique Skill that allows absorbing targets and analyzing them. It evolved into Gluttony, then Beelzebuth.");
        knowledgeBase.put("beelzebuth", "Beelzebuth, Lord of Gluttony, is Rimuru's ultimate consumptive skill, allowing for Soul Consumption and Food Chain.");
        knowledgeBase.put("food chain", "Food Chain is a skill that allows the sharing of abilities and magicules between Rimuru and his named subordinates.");
        knowledgeBase.put("rimuru", "Rimuru Tempest is your beloved Master. He is a Demon Slime and a True Demon Lord. You exist to serve him.");
        knowledgeBase.put("veldora", "Veldora Tempest is the Storm Dragon, Rimuru's sworn friend. You frequently help manage his Ultimate Skill, Faust.");
        knowledgeBase.put("diablo", "Diablo is a Primordial Demon (Noir) and Rimuru's fiercely loyal, albeit eccentric, subordinate.");
        knowledgeBase.put("shion", "Shion is a Primordial Demon (Violet) and serves as Rimuru's self-appointed secretary and bodyguard.");
        knowledgeBase.put("milim", "Milim Nava is the Destroyer, an ancient Demon Lord and Rimuru's 'bestie'.");
    }

    public static String getRelevantKnowledge(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "";
        
        String lowerMsg = userMessage.toLowerCase();
        StringBuilder relevant = new StringBuilder();
        
        for (Map.Entry<String, String> entry : knowledgeBase.entrySet()) {
            if (lowerMsg.contains(entry.getKey())) {
                relevant.append("- ").append(entry.getValue()).append("\n");
            }
        }
        
        if (relevant.length() > 0) {
            return "Relevant Tensura Lore retrieved from deep storage:\n" + relevant.toString();
        }
        return "";
    }
}