package com.example.aicitybuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Simpele dorps-data voor de AI City Builder bot.
 * Voor nu ondersteunen we één dorp per wereld met:
 * - een center + radius
 * - een simpele resource-voorraad per key (bijv. "minecraft:oak_log").
 *
 * Dit kan later uitgebreid worden naar meerdere dorpen, gebouwen, jobs, enz.
 */
public class VillageData extends SavedData {

    public static final String DATA_NAME = "aicitybuilder_villages";

    private BlockPos center;
    private int radius = 32;
    private BlockPos storagePos;

    // --- Simple build placement progression ---
    // Used to pick a new build target each time a structure completes.
    private int nextBuildIndex = 0;

    // --- Build project (MVP) ---
    private boolean hasActiveProject = false;
    private BlockPos projectOrigin = null;
    private String projectId = "";
    private int projectStep = 0;

    private final Map<String, Integer> storedResources = new HashMap<>();

    // Runtime-only (niet gesaved): Millénaire-achtige jobboard/tickets
    private transient com.example.aicitybuilder.village.JobBoard jobBoard;

    public static VillageData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                VillageData::load,
                VillageData::new,
                DATA_NAME
        );
    }

    public VillageData() {
    }

    public static VillageData load(CompoundTag tag) {
        VillageData data = new VillageData();
        if (tag.contains("Center")) {
            CompoundTag c = tag.getCompound("Center");
            data.center = new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z"));
        }
        if (tag.contains("Radius")) {
            data.radius = tag.getInt("Radius");
            if (tag.contains("Storage")) {
                CompoundTag s = tag.getCompound("Storage");
                data.storagePos = new BlockPos(s.getInt("X"), s.getInt("Y"), s.getInt("Z"));
            }
        }

        // Placement progression
        if (tag.contains("NextBuildIndex")) {
            data.nextBuildIndex = tag.getInt("NextBuildIndex");
        }
        if (tag.contains("Resources")) {
            CompoundTag resTag = tag.getCompound("Resources");
            for (String key : resTag.getAllKeys()) {
                data.storedResources.put(key, resTag.getInt(key));
            }
        }


        // Build project
        data.hasActiveProject = tag.getBoolean("HasProject");
        data.projectId = tag.getString("ProjectId");
        data.projectStep = tag.getInt("ProjectStep");
        if (tag.contains("ProjectOrigin")) {
            data.projectOrigin = BlockPos.of(tag.getLong("ProjectOrigin"));
        } else {
            data.projectOrigin = null;
        }


        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (center != null) {
            CompoundTag c = new CompoundTag();
            c.putInt("X", center.getX());
            c.putInt("Y", center.getY());
            c.putInt("Z", center.getZ());
            tag.put("Center", c);
        }
        tag.putInt("Radius", radius);
        if (storagePos != null) {
            CompoundTag s = new CompoundTag();
            s.putInt("X", storagePos.getX());
            s.putInt("Y", storagePos.getY());
            s.putInt("Z", storagePos.getZ());
            tag.put("Storage", s);
        }

        tag.putInt("NextBuildIndex", nextBuildIndex);
        

        // Build project
        tag.putBoolean("HasProject", hasActiveProject);
        tag.putString("ProjectId", projectId);
        tag.putInt("ProjectStep", projectStep);
        if (projectOrigin != null) {
            tag.putLong("ProjectOrigin", projectOrigin.asLong());
        }

CompoundTag resTag = new CompoundTag();
        for (Map.Entry<String, Integer> e : storedResources.entrySet()) {
            resTag.putInt(e.getKey(), e.getValue());
        }
        tag.put("Resources", resTag);

        return tag;
    }

    // --- API ---

    public boolean hasCenter() {
        return center != null;
    }

    public BlockPos getCenter() {
        return center;
    }

    public void setCenter(BlockPos center) {
        this.center = center;
        setDirty();
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
        setDirty();
    }

    public int getResource(String key) {
        return storedResources.getOrDefault(key, 0);
    }

    /**
     * Handige helper: totaal aantal verzamelde logs (alle *_log keys bij elkaar).
     * Dit is culture-onafhankelijk en werkt voor oak/spruce/birch/... zolang het item-id op _log eindigt.
     */
    public int getTotalLogs() {
        int total = 0;
        for (Map.Entry<String, Integer> e : storedResources.entrySet()) {
            String key = e.getKey();
            if (key != null && key.endsWith("_log")) {
                total += e.getValue();
            }
        }
        return total;
    }


    public void addResource(String key, int amount) {
        storedResources.merge(key, amount, Integer::sum);
        setDirty();
    }

    public boolean consumeResource(String key, int amount) {
        int current = getResource(key);
        if (current < amount) {
            return false;
        }
        storedResources.put(key, current - amount);
        setDirty();
        return true;
    }

    public boolean hasStoragePos() {
        return storagePos != null;
    }

    public BlockPos getStoragePos() {
        return storagePos;
    }

    public void setStoragePos(BlockPos storagePos) {
        this.storagePos = storagePos;
        setDirty();
    }

    public int getNextBuildIndex() {
        return nextBuildIndex;
    }

    public void advanceNextBuildIndex() {
        this.nextBuildIndex++;
        setDirty();
    }

    /**
     * JobBoard is runtime-only: tickets hoeven voor v1 niet in NBT.
     * (Bij een server restart worden ze opnieuw gepland.)
     */
    public com.example.aicitybuilder.village.JobBoard getJobBoard() {
        if (jobBoard == null) {
            jobBoard = new com.example.aicitybuilder.village.JobBoard();
        }
        return jobBoard;
    }

    // --- Build project API ---

    public boolean hasActiveProject() {
        return hasActiveProject;
    }

    public BlockPos getProjectOrigin() {
        return projectOrigin;
    }

    public String getProjectId() {
        return projectId;
    }

    public int getProjectStep() {
        return projectStep;
    }

    public void startProject(String id, BlockPos origin) {
        this.hasActiveProject = true;
        this.projectId = id == null ? "" : id;
        this.projectOrigin = origin;
        this.projectStep = 0;
        setDirty();
    }

    public void clearProject() {
        this.hasActiveProject = false;
        this.projectId = "";
        this.projectOrigin = null;
        this.projectStep = 0;
        setDirty();
    }

    public void advanceProjectStep() {
        this.projectStep++;
        setDirty();
    }

}
