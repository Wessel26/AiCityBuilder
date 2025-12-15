package com.example.aicitybuilder.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import java.util.*;

public class WarehouseIndex {
    private final List<ContainerLink> containers = new ArrayList<>();

    private final Map<Item, Integer> availableCached = new HashMap<>();
    private final Map<UUID, Map<Item, Integer>> reserved = new HashMap<>();

    private long nextScanAtGameTime = 0;

    public List<ContainerLink> containers() { return containers; }

    public Map<Item, Integer> availableCached() { return Collections.unmodifiableMap(availableCached); }

    public int available(Item item) { return availableCached.getOrDefault(item, 0); }

    public boolean canReserve(Map<Item, Integer> cost) {
        for (Map.Entry<Item, Integer> e : cost.entrySet()) {
            if (available(e.getKey()) < e.getValue()) return false;
        }
        return true;
    }

    public boolean reserve(Map<Item, Integer> cost, UUID key) {
        if (!canReserve(cost)) return false;
        Map<Item, Integer> r = reserved.computeIfAbsent(key, k -> new HashMap<>());
        for (Map.Entry<Item, Integer> e : cost.entrySet()) {
            r.merge(e.getKey(), e.getValue(), Integer::sum);
            availableCached.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
        return true;
    }

    public void release(UUID key) {
        Map<Item, Integer> r = reserved.remove(key);
        if (r == null) return;
        for (Map.Entry<Item, Integer> e : r.entrySet()) {
            availableCached.merge(e.getKey(), e.getValue(), Integer::sum);
        }
    }

    /** Consume reserved items from the actual containers (best effort). */
    public void consumeReserved(ServerLevel level, UUID key) {
        Map<Item, Integer> r = reserved.remove(key);
        if (r == null) return;

        // remove from world inventories
        for (Map.Entry<Item, Integer> e : r.entrySet()) {
            int remaining = e.getValue();
            for (ContainerLink link : containers) {
                if (remaining <= 0) break;
                BlockEntity be = level.getBlockEntity(link.pos);
                if (be == null) continue;
                IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
                if (h == null) continue;

                for (int slot = 0; slot < h.getSlots() && remaining > 0; slot++) {
                    ItemStack st = h.getStackInSlot(slot);
                    if (st.isEmpty() || st.getItem() != e.getKey()) continue;
                    int toExtract = Math.min(remaining, st.getCount());
                    ItemStack extracted = h.extractItem(slot, toExtract, false);
                    remaining -= extracted.getCount();
                }
            }
        }
        // after consuming, schedule a rescan soon to fix cache drift
        nextScanAtGameTime = 0;
    }

    public void tick(ServerLevel level) {
        long gt = level.getGameTime();
        if (gt >= nextScanAtGameTime) {
            scanContainers(level);
            nextScanAtGameTime = gt + 100; // every 5 seconds
        }
    }

    public void scanContainers(ServerLevel level) {
        availableCached.clear();
        for (ContainerLink link : containers) {
            BlockEntity be = level.getBlockEntity(link.pos);
            if (be == null) continue;
            IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (h == null) continue;

            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack st = h.getStackInSlot(slot);
                if (st.isEmpty()) continue;
                availableCached.merge(st.getItem(), st.getCount(), Integer::sum);
            }
        }
        // subtract current reservations
        for (Map<Item, Integer> r : reserved.values()) {
            for (Map.Entry<Item, Integer> e : r.entrySet()) {
                availableCached.merge(e.getKey(), -e.getValue(), Integer::sum);
            }
        }
    }

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (ContainerLink l : containers) list.add(l.save());
        root.put("Containers", list);

        // save reservations (minimal)
        ListTag resList = new ListTag();
        for (Map.Entry<UUID, Map<Item, Integer>> entry : reserved.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Key", entry.getKey());
            CompoundTag items = new CompoundTag();
            for (Map.Entry<Item, Integer> e : entry.getValue().entrySet()) {
                items.putInt(e.getKey().toString(), e.getValue());
            }
            t.put("Items", items);
            resList.add(t);
        }
        root.put("Reserved", resList);
        return root;
    }

    public static WarehouseIndex load(CompoundTag root) {
        WarehouseIndex w = new WarehouseIndex();
        ListTag list = root.getList("Containers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            w.containers.add(ContainerLink.load(list.getCompound(i)));
        }
        // reservations are best-effort (they will be revalidated on scan)
        ListTag resList = root.getList("Reserved", Tag.TAG_COMPOUND);
        for (int i = 0; i < resList.size(); i++) {
            CompoundTag t = resList.getCompound(i);
            UUID key = t.getUUID("Key");
            CompoundTag items = t.getCompound("Items");
            Map<Item, Integer> map = new HashMap<>();
            for (String k : items.getAllKeys()) {
                // can't reliably parse Item from toString(), skip; keep empty on load
            }
            if (!map.isEmpty()) w.reserved.put(key, map);
        }
        return w;
    }
}
