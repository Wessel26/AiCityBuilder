package com.example.aicitybuilder.settlement;

import com.example.aicitybuilder.structures.TemplateRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public class SettlementManager {
    private final ServerLevel level;
    private final SettlementSavedData store;

    private final Map<BlockPos, UUID> centerLookup = new HashMap<>();

    private SettlementManager(ServerLevel level) {
        this.level = level;
        this.store = SettlementSavedData.get(level);
        rebuildLookup();
    }

    private void rebuildLookup() {
        centerLookup.clear();
        for (SettlementData s : store.settlements.values()) {
            centerLookup.put(s.center, s.id);
        }
    }
    private static final Map<ServerLevel, SettlementManager> RUNTIME = new WeakHashMap<>();

    public static SettlementManager runtime(ServerLevel level) {
        return RUNTIME.computeIfAbsent(level, SettlementManager::new);
    }

    public SettlementData getOrCreateAt(BlockPos centerPos) {
        UUID existing = centerLookup.get(centerPos);
        if (existing != null) return store.settlements.get(existing);

        SettlementData s = new SettlementData(UUID.randomUUID(), centerPos);
        store.settlements.put(s.id, s);
        centerLookup.put(centerPos, s.id);

        // default: link a chest next to center if present
        s.warehouse.containers().add(new com.example.aicitybuilder.storage.ContainerLink(centerPos.offset(1, 0, 0), "default"));
        store.setDirty();
        return s;
    }

    public Optional<SettlementData> getNearest(BlockPos pos, int range) {
        double best = Double.MAX_VALUE;
        SettlementData bestS = null;
        for (SettlementData s : store.settlements.values()) {
            double d = s.center.distSqr(pos);
            if (d < best && d <= (double)range * range) {
                best = d;
                bestS = s;
            }
        }
        return Optional.ofNullable(bestS);
    }

    public void tickAll() {
        // ensure template registry loaded at least once (server might have started without reload event)
        if (TemplateRegistry.get().all().isEmpty()) {
            TemplateRegistry.get().loadDefault(level.getServer().getResourceManager());
        }

        for (SettlementData s : store.settlements.values()) {
            s.tick(level);
        }
        store.setDirty();
    }
}
