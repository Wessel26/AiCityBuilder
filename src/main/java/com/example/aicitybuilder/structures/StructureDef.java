package com.example.aicitybuilder.structures;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Datagedreven definitie van een structuur (template + cost + prerequisites).
 *
 * Extended metadata (optioneel in JSON):
 * - housing: int   -> hoeveel inwoners deze structuur ondersteunt
 * - food: int      -> hoeveel food-units per day (20 min) de settlement produceert
 * - defense: int   -> defensiewaarde (guards/walls/towers/town hall)
 * - market: bool   -> of deze structuur handelen unlockt
 */
public final class StructureDef {
    private final String id;
    private final Component name;
    private final ResourceLocation template;
    private final Map<Item, Integer> cost;
    private final List<String> prerequisites;
    private final int minLevel;
    @Nullable private final String upgradeTo;
    private final List<ResourceLocation> stages;

    // Optional metadata
    private final int housing;
    private final int food;
    private final int defense;
    private final boolean market;

    public StructureDef(
            String id,
            Component name,
            ResourceLocation template,
            Map<Item, Integer> cost,
            List<String> prerequisites,
            int minLevel,
            @Nullable String upgradeTo,
            List<ResourceLocation> stages,
            int housing,
            int food,
            int defense,
            boolean market
    ) {
        this.id = id;
        this.name = name;
        this.template = template;
        this.cost = cost == null ? Collections.emptyMap() : Collections.unmodifiableMap(cost);
        this.prerequisites = prerequisites == null ? Collections.emptyList() : Collections.unmodifiableList(prerequisites);
        this.minLevel = minLevel;
        this.upgradeTo = upgradeTo;
        this.stages = stages == null ? Collections.emptyList() : Collections.unmodifiableList(stages);

        this.housing = Math.max(0, housing);
        this.food = Math.max(0, food);
        this.defense = Math.max(0, defense);
        this.market = market;
    }

    /** Backwards-compatible constructor (no metadata). */
    public StructureDef(
            String id,
            Component name,
            ResourceLocation template,
            Map<Item, Integer> cost,
            List<String> prerequisites,
            int minLevel,
            @Nullable String upgradeTo,
            List<ResourceLocation> stages
    ) {
        this(id, name, template, cost, prerequisites, minLevel, upgradeTo, stages, 0, 0, 0, false);
    }

    public String id() { return id; }
    public Component name() { return name; }
    public ResourceLocation template() { return template; }
    public Map<Item, Integer> cost() { return cost; }
    public List<String> prerequisites() { return prerequisites; }
    public int minLevel() { return minLevel; }
    @Nullable public String upgradeTo() { return upgradeTo; }
    public List<ResourceLocation> stages() { return stages; }

    // metadata getters
    public int housing() { return housing; }
    public int food() { return food; }
    public int defense() { return defense; }
    public boolean market() { return market; }
}
