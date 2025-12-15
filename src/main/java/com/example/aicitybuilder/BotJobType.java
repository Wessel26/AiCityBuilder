package com.example.aicitybuilder;

/**
 * Eenvoudige rollen / banen voor de stad-bot.
 * Dit kan later uitgebreid worden met meer gedragslogica per job.
 */
public enum BotJobType {
    LUMBERJACK, // Houthakker
    HAULER,     // Sjouwer / transporteur (brengt items naar opslag)
    MINER,      // Mijnwerker
    BUILDER,    // Bouwer
    FARMER,     // Boer
    TRADER,// Handelaar
    CRAFTER;

    public String getDisplayName() {
        return switch (this) {
            case LUMBERJACK -> "Houthakker";
            case HAULER -> "Sjouwer";
            case MINER -> "Mijnwerker";
            case BUILDER -> "Bouwer";
            case FARMER -> "Boer";
            case TRADER -> "Handelaar";
            case CRAFTER -> "Ambachtsman";
        };
    }
}
