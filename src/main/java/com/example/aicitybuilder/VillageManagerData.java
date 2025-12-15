package com.example.aicitybuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/**
 * Bewaart meerdere dorpen per wereld/dimension.
 *
 * Dit is de basis voor "Millénaire-achtige" wereldgeneratie:
 * - meerdere dorpen
 * - minimum-afstand tussen dorpen
 * - eenvoudige lookup (nearest)
 */
public class VillageManagerData extends SavedData {

    public static final String DATA_NAME = "aicitybuilder_village_manager";

    /** Default minimum afstand (blokken) tussen dorpcentra. */
    public static final int DEFAULT_MIN_DISTANCE = 512;

    /** Default radius voor een nieuw dorp. */
    public static final int DEFAULT_RADIUS = 64;

    private final Map<UUID, VillageRecord> villages = new LinkedHashMap<>();

    public static VillageManagerData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                VillageManagerData::load,
                VillageManagerData::new,
                DATA_NAME
        );
    }

    public VillageManagerData() {
    }

    public static VillageManagerData load(CompoundTag tag) {
        VillageManagerData data = new VillageManagerData();
        if (tag.contains("Villages", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Villages", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag vTag = list.getCompound(i);
                VillageRecord rec = VillageRecord.fromNbt(vTag);
                if (rec != null) {
                    data.villages.put(rec.id, rec);
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (VillageRecord rec : villages.values()) {
            list.add(rec.toNbt());
        }
        tag.put("Villages", list);
        return tag;
    }

    // --- API ---

    public Collection<VillageRecord> getVillages() {
        return Collections.unmodifiableCollection(villages.values());
    }

    public Optional<VillageRecord> getVillage(UUID id) {
        return Optional.ofNullable(villages.get(id));
    }

    /**
     * Check of er al een dorp "in" deze chunk bestaat (center in dezelfde chunk).
     */
    public boolean hasVillageInChunk(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        for (VillageRecord rec : villages.values()) {
            if (rec.dimension.equals(dimension.location().toString()) && rec.homeChunkLong == key) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kan hier een dorp geplaatst worden, rekening houdend met minimum afstand.
     */
    public boolean canPlaceVillageHere(ServerLevel level, BlockPos center) {
        return canPlaceVillageHere(level, center, DEFAULT_MIN_DISTANCE);
    }

    public boolean canPlaceVillageHere(ServerLevel level, BlockPos center, int minDistance) {
        ResourceKey<Level> dim = level.dimension();
        int cx = center.getX();
        int cz = center.getZ();
        int minDistSq = minDistance * minDistance;

        // Extra snelle check: al een dorp in dezelfde chunk? (voorkomt dubbelspawns)
        ChunkPos cp = new ChunkPos(center);
        if (hasVillageInChunk(dim, cp.x, cp.z)) {
            return false;
        }

        for (VillageRecord rec : villages.values()) {
            if (!rec.dimension.equals(dim.location().toString())) continue;
            long dx = (long) rec.centerX - cx;
            long dz = (long) rec.centerZ - cz;
            long distSq = dx * dx + dz * dz;
            if (distSq < minDistSq) {
                return false;
            }
        }
        return true;
    }

    /**
     * Maak een nieuw dorp aan.
     */
    public VillageRecord createVillage(ServerLevel level, BlockPos center) {
        UUID id = UUID.randomUUID();
        VillageRecord rec = VillageRecord.create(id, level.dimension(), center, DEFAULT_RADIUS);
        villages.put(id, rec);
        setDirty();
        return rec;
    }

    /**
     * Vind het dichtstbijzijnde dorp binnen maxDistance (horizontaal gemeten).
     */
    public Optional<VillageRecord> findNearest(ServerLevel level, BlockPos pos, int maxDistance) {
        ResourceKey<Level> dim = level.dimension();
        int px = pos.getX();
        int pz = pos.getZ();
        int maxDistSq = maxDistance * maxDistance;

        VillageRecord best = null;
        long bestSq = Long.MAX_VALUE;

        for (VillageRecord rec : villages.values()) {
            if (!rec.dimension.equals(dim.location().toString())) continue;
            long dx = (long) rec.centerX - px;
            long dz = (long) rec.centerZ - pz;
            long distSq = dx * dx + dz * dz;
            if (distSq <= maxDistSq && distSq < bestSq) {
                bestSq = distSq;
                best = rec;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Update opslaglocatie voor een dorp.
     */
    public void setStoragePos(UUID villageId, BlockPos storage) {
        VillageRecord rec = villages.get(villageId);
        if (rec == null) return;
        rec.storageX = storage.getX();
        rec.storageY = storage.getY();
        rec.storageZ = storage.getZ();
        setDirty();
    }

    // --- Record ---

    public static final class VillageRecord {
        public final UUID id;
        /** dimension als string (ResourceLocation) */
        public final String dimension;
        public final int centerX, centerY, centerZ;
        public final int radius;

        /** opgeslagen storage pos; -1 = none */
        public int storageX = Integer.MIN_VALUE;
        public int storageY = Integer.MIN_VALUE;
        public int storageZ = Integer.MIN_VALUE;

        /** chunk key van center */
        public final long homeChunkLong;

        private VillageRecord(UUID id, String dimension, int cx, int cy, int cz, int radius, long homeChunkLong) {
            this.id = id;
            this.dimension = dimension;
            this.centerX = cx;
            this.centerY = cy;
            this.centerZ = cz;
            this.radius = radius;
            this.homeChunkLong = homeChunkLong;
        }

        public static VillageRecord create(UUID id, ResourceKey<Level> dim, BlockPos center, int radius) {
            ChunkPos cp = new ChunkPos(center);
            return new VillageRecord(
                    id,
                    dim.location().toString(),
                    center.getX(),
                    center.getY(),
                    center.getZ(),
                    radius,
                    ChunkPos.asLong(cp.x, cp.z)
            );
        }

        public BlockPos centerPos() {
            return new BlockPos(centerX, centerY, centerZ);
        }

        public boolean hasStorage() {
            return storageX != Integer.MIN_VALUE;
        }

        public Optional<BlockPos> storagePos() {
            if (!hasStorage()) return Optional.empty();
            return Optional.of(new BlockPos(storageX, storageY, storageZ));
        }

        public CompoundTag toNbt() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putString("Dim", dimension);
            t.putInt("X", centerX);
            t.putInt("Y", centerY);
            t.putInt("Z", centerZ);
            t.putInt("Radius", radius);
            if (hasStorage()) {
                CompoundTag s = new CompoundTag();
                s.putInt("X", storageX);
                s.putInt("Y", storageY);
                s.putInt("Z", storageZ);
                t.put("Storage", s);
            }
            t.putLong("HomeChunk", homeChunkLong);
            return t;
        }

        public static VillageRecord fromNbt(CompoundTag t) {
            if (!t.hasUUID("Id") || !t.contains("Dim")) return null;
            UUID id = t.getUUID("Id");
            String dim = t.getString("Dim");
            int x = t.getInt("X");
            int y = t.getInt("Y");
            int z = t.getInt("Z");
            int radius = t.contains("Radius") ? t.getInt("Radius") : DEFAULT_RADIUS;
            long homeChunk = t.contains("HomeChunk") ? t.getLong("HomeChunk") : ChunkPos.asLong(new ChunkPos(new BlockPos(x, y, z)).x, new ChunkPos(new BlockPos(x, y, z)).z);

            VillageRecord rec = new VillageRecord(id, dim, x, y, z, radius, homeChunk);
            if (t.contains("Storage", Tag.TAG_COMPOUND)) {
                CompoundTag s = t.getCompound("Storage");
                rec.storageX = s.getInt("X");
                rec.storageY = s.getInt("Y");
                rec.storageZ = s.getInt("Z");
            }
            return rec;
        }
    }
}
