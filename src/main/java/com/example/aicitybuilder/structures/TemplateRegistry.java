package com.example.aicitybuilder.structures;

import com.example.aicitybuilder.cityaibot;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.util.*;

public class TemplateRegistry {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();

    private static final TemplateRegistry INSTANCE = new TemplateRegistry();

    public static TemplateRegistry get() { return INSTANCE; }

    private final Map<String, StructureDef> defs = new HashMap<>();

    public void clear() { defs.clear(); }

    public Collection<StructureDef> all() { return Collections.unmodifiableCollection(defs.values()); }

    @Nullable
    public StructureDef get(String id) { return defs.get(id); }

    /** Load default settlement defs from datapacks, with a hardcoded fallback set. */
    public void loadDefault(ResourceManager rm) {
        clear();
        int loaded = 0;
        String prefix = "data/" + cityaibot.MODID + "/settlements/default/structures/";
        try {
            for (ResourceLocation rl : rm.listResources("settlements/default/structures", p -> p.getPath().endsWith(".json")).keySet()) {
                // rl is like aicitybuilder:settlements/default/structures/town_hall_1.json
                Resource res = rm.getResource(rl).orElse(null);
                if (res == null) continue;
                try (BufferedReader br = res.openAsReader()) {
                    JsonObject obj = GSON.fromJson(br, JsonObject.class);
                    StructureDef def = parseDef(obj);
                    defs.put(def.id(), def);
                    loaded++;
                } catch (Exception e) {
                    LOGGER.error("Failed to parse structure def {}", rl, e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed scanning structure defs", e);
        }

        if (loaded == 0) {
            LOGGER.warn("No structure JSONs found; using hardcoded fallback set.");
            addFallbacks();
        } else {
            // ensure minimum required fallback if specific ids are missing
            ensureFallback("town_hall_1");
            ensureFallback("house_1");
            ensureFallback("lumber_camp_1");
        }

        LOGGER.info("Loaded {} structure defs (total {}).", loaded, defs.size());
    }

    private void ensureFallback(String id) {
        if (!defs.containsKey(id)) {
            addFallbacks();
        }
    }

    private void addFallbacks() {
        defs.put("town_hall_1", new StructureDef(
                "town_hall_1",
                Component.literal("Town Hall I"),
                new ResourceLocation(cityaibot.MODID, "structures/town_hall_1"),
                Map.of(Items.OAK_PLANKS, 64),
                List.of(),
                1,
                "town_hall_2",
                List.of()
        ));
        defs.put("house_1", new StructureDef(
                "house_1",
                Component.literal("House I"),
                new ResourceLocation(cityaibot.MODID, "structures/house_1"),
                Map.of(Items.OAK_PLANKS, 32),
                List.of("town_hall_1"),
                1,
                null,
                List.of()
        ));
        defs.put("lumber_camp_1", new StructureDef(
                "lumber_camp_1",
                Component.literal("Lumber Camp I"),
                new ResourceLocation(cityaibot.MODID, "structures/lumber_camp_1"),
                Map.of(Items.OAK_LOG, 32),
                List.of("town_hall_1"),
                1,
                null,
                List.of()
        ));
    }

    private static StructureDef parseDef(JsonObject obj) {
        String id = obj.get("id").getAsString();
        String name = obj.has("name") ? obj.get("name").getAsString() : id;
        ResourceLocation template = new ResourceLocation(obj.get("template").getAsString());

        Map<Item, Integer> cost = new HashMap<>();
        if (obj.has("cost") && obj.get("cost").isJsonObject()) {
            JsonObject c = obj.getAsJsonObject("cost");
            for (String key : c.keySet()) {
                Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(key));
                if (it != null) cost.put(it, c.get(key).getAsInt());
            }
        }

        List<String> prereq = new ArrayList<>();
        if (obj.has("prerequisites")) {
            JsonArray a = obj.getAsJsonArray("prerequisites");
            for (JsonElement e : a) prereq.add(e.getAsString());
        }
        int minLevel = obj.has("minLevel") ? obj.get("minLevel").getAsInt() : 1;
        String upgradeTo = obj.has("upgradeTo") ? obj.get("upgradeTo").getAsString() : null;

        List<ResourceLocation> stages = new ArrayList<>();
        if (obj.has("stages")) {
            JsonArray a = obj.getAsJsonArray("stages");
            for (JsonElement e : a) stages.add(new ResourceLocation(e.getAsString()));
        }

        // Optional metadata (all safe defaults)
        int housing = obj.has("housing") ? obj.get("housing").getAsInt() : 0;
        int food = obj.has("food") ? obj.get("food").getAsInt() : 0;
        int defense = obj.has("defense") ? obj.get("defense").getAsInt() : 0;
        boolean market = obj.has("market") && obj.get("market").getAsBoolean();

        return new StructureDef(id, Component.literal(name), template, cost, prereq, minLevel, upgradeTo, stages, housing, food, defense, market);
    }
}
