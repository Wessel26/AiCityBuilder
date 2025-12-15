package com.example.aicitybuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class VillageData extends SavedData {
    public static final String DATA_NAME = "aicitybuilder_villages";

    private BlockPos center;
    private int radius = 32;
    private BlockPos storagePos;

    private int nextBuildIndex = 0;

    // --- Build project (MVP) ---
    private boolean hasActiveProject = false;
    private BlockPos projectOrigin = null;
    private String projectId = "";
    private int projectStep = 0;

    private final Map<String, Integer> storedResources = new HashMap<>();

    // Build queue
    private final Deque<QueuedProject> buildQueue = new ArrayDeque<>();

    // voorkomt dat we bij elke load opnieuw 3 houses enqueuen
    private boolean queueInitialized = false;

    // --- POPULATION (Stap 10) ---
    private int population = 0;          // actuele bots (gesynct)
    private int populationCap = 1;       // max bots toegestaan
    private long lastSpawnGameTime = 0;  // throttle

    // Runtime-only
    private transient com.example.aicitybuilder.village.JobBoard jobBoard;

    public static VillageData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                VillageData::load,
                VillageData::new,
                DATA_NAME
        );
    }

    public VillageData() {}

    public static VillageData load(CompoundTag tag) {
        VillageData data = new VillageData();

        if (tag.contains("Center")) {
            CompoundTag c = tag.getCompound("Center");
            data.center = new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z"));
        }

        if (tag.contains("Radius")) data.radius = tag.getInt("Radius");

        if (tag.contains("Storage")) {
            CompoundTag s = tag.getCompound("Storage");
            data.storagePos = new BlockPos(s.getInt("X"), s.getInt("Y"), s.getInt("Z"));
        }

        if (tag.contains("NextBuildIndex")) data.nextBuildIndex = tag.getInt("NextBuildIndex");

        if (tag.contains("Resources")) {
            CompoundTag resTag = tag.getCompound("Resources");
            for (String key : resTag.getAllKeys()) {
                data.storedResources.put(key, resTag.getInt(key));
            }
        }

        data.hasActiveProject = tag.getBoolean("HasProject");
        data.projectId = tag.getString("ProjectId");
        data.projectStep = tag.getInt("ProjectStep");
        if (tag.contains("ProjectOrigin")) {
            data.projectOrigin = BlockPos.of(tag.getLong("ProjectOrigin"));
        } else {
            data.projectOrigin = null;
        }

        if (tag.contains("BuildQueue")) {
            ListTag list = tag.getList("BuildQueue", CompoundTag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag q = list.getCompound(i);
                String id = q.getString("Id");
                long originLong = q.getLong("Origin");
                data.buildQueue.addLast(new QueuedProject(id, BlockPos.of(originLong)));
            }
        }

        data.queueInitialized = tag.getBoolean("QueueInitialized");

        // POPULATION
        if (tag.contains("Population")) data.population = tag.getInt("Population");
        if (tag.contains("PopulationCap")) data.populationCap = tag.getInt("PopulationCap");
        if (tag.contains("LastSpawn")) data.lastSpawnGameTime = tag.getLong("LastSpawn");

        // safety
        if (data.populationCap < 1) data.populationCap = 1;
        if (data.population < 0) data.population = 0;

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

        tag.putBoolean("HasProject", hasActiveProject);
        tag.putString("ProjectId", projectId);
        tag.putInt("ProjectStep", projectStep);
        if (projectOrigin != null) tag.putLong("ProjectOrigin", projectOrigin.asLong());

        CompoundTag resTag = new CompoundTag();
        for (Map.Entry<String, Integer> e : storedResources.entrySet()) {
            resTag.putInt(e.getKey(), e.getValue());
        }
        tag.put("Resources", resTag);

        ListTag qList = new ListTag();
        for (QueuedProject qp : buildQueue) {
            CompoundTag q = new CompoundTag();
            q.putString("Id", qp.id);
            q.putLong("Origin", qp.origin.asLong());
            qList.add(q);
        }
        tag.put("BuildQueue", qList);

        tag.putBoolean("QueueInitialized", queueInitialized);

        // POPULATION
        tag.putInt("Population", population);
        tag.putInt("PopulationCap", populationCap);
        tag.putLong("LastSpawn", lastSpawnGameTime);

        return tag;
    }

    // --- Basic API ---
    public boolean hasCenter() { return center != null; }
    public BlockPos getCenter() { return center; }
    public void setCenter(BlockPos center) { this.center = center; setDirty(); }

    public boolean hasStoragePos() { return storagePos != null; }
    public BlockPos getStoragePos() { return storagePos; }
    public void setStoragePos(BlockPos storagePos) { this.storagePos = storagePos; setDirty(); }

    public int getTotalLogs() {
        int total = 0;
        for (Map.Entry<String, Integer> e : storedResources.entrySet()) {
            String key = e.getKey();
            if (key != null && key.endsWith("_log")) total += e.getValue();
        }
        return total;
    }

    public com.example.aicitybuilder.village.JobBoard getJobBoard() {
        if (jobBoard == null) jobBoard = new com.example.aicitybuilder.village.JobBoard();
        return jobBoard;
    }

    // --- Project API ---
    public boolean hasActiveProject() { return hasActiveProject; }
    public BlockPos getProjectOrigin() { return projectOrigin; }
    public String getProjectId() { return projectId; }
    public int getProjectStep() { return projectStep; }

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

    public void completeProjectAndStartNext() {
        this.hasActiveProject = false;
        this.projectId = "";
        this.projectOrigin = null;
        this.projectStep = 0;

        QueuedProject next = buildQueue.pollFirst();
        if (next != null && next.id != null && !next.id.isEmpty() && next.origin != null) {
            this.hasActiveProject = true;
            this.projectId = next.id;
            this.projectOrigin = next.origin;
            this.projectStep = 0;
        }

        setDirty();
    }

    public void advanceProjectStep() { this.projectStep++; setDirty(); }

    // --- Queue API ---
    public int getBuildQueueSize() { return buildQueue.size(); }

    public void enqueueProject(String id, BlockPos origin) {
        if (id == null || id.isEmpty() || origin == null) return;
        buildQueue.addLast(new QueuedProject(id, origin));
        setDirty();
    }

    public void enqueueProjectAutoOrigin(String id) {
        if (center == null) return;
        BlockPos origin = computeAutoOrigin(center, nextBuildIndex);
        nextBuildIndex++;
        enqueueProject(id, origin);
        setDirty();
    }

    public void startNextFromQueueIfIdle() {
        if (hasActiveProject) return;
        QueuedProject next = buildQueue.pollFirst();
        if (next == null) return;
        startProject(next.id, next.origin);
        setDirty();
    }

    // --- Auto bootstrap queue ---
    public boolean isQueueInitialized() { return queueInitialized; }

    public void bootstrapQueueIfNeeded() {
        if (queueInitialized) return;
        if (center == null) return;

        enqueueProjectAutoOrigin("tiny_house");
        enqueueProjectAutoOrigin("tiny_house");
        enqueueProjectAutoOrigin("tiny_house");

        queueInitialized = true;

        // baseline cap: 1 bot is ok; houses will add more
        if (populationCap < 1) populationCap = 1;

        setDirty();
    }

    private static BlockPos computeAutoOrigin(BlockPos center, int index) {
        int col = index % 3;
        int row = index / 3;
        int spacing = 10;
        int dx = 6 + col * spacing;
        int dz = 6 + row * spacing;
        return center.offset(dx, 0, dz);
    }

    // --- POPULATION API (Stap 10) ---

    public int getPopulation() { return population; }
    public int getPopulationCap() { return populationCap; }

    public void setPopulation(int population) {
        this.population = Math.max(0, population);
        setDirty();
    }

    public void setPopulationCap(int cap) {
        this.populationCap = Math.max(1, cap);
        setDirty();
    }

    public boolean canSpawnCitizen(long nowGameTime) {
        if (!hasCenter()) return false;
        if (population >= populationCap) return false;
        // throttle: max 1 per 10 sec
        return (nowGameTime - lastSpawnGameTime) >= 200L;
    }

    public void markSpawned(long nowGameTime) {
        this.lastSpawnGameTime = nowGameTime;
        setDirty();
    }

    /**
     * Millénaire vibe: houses verhogen de cap.
     * MVP: tiny_house completion => +1 cap
     */
    public void onProjectCompleted(String completedProjectId) {
        if (completedProjectId == null) return;

        if (completedProjectId.equals("tiny_house")) {
            populationCap += 1;
            setDirty();
        }
    }

    private static class QueuedProject {
        final String id;
        final BlockPos origin;

        QueuedProject(String id, BlockPos origin) {
            this.id = id;
            this.origin = origin;
        }
    }
}
