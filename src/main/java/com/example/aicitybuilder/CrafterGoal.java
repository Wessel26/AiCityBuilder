package com.example.aicitybuilder;

import com.example.aicitybuilder.settlement.SettlementState;
import com.example.aicitybuilder.village.JobTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * MVP Crafter:
 * - ticket-driven (CRAFTER ticket)
 * - loopt naar storagePos
 * - zet logs om in planks (1 log -> 4 planks) door chest-inventory te manipuleren
 * - update SettlementState ledger synchroon (consume log, add planks)
 *
 * Nu: ondersteunt alle vanilla wood types via string mapping: *_log -> *_planks
 */
public class CrafterGoal extends Goal {

    private static final int CRAFTS_PER_TICK = 1; // klein houden voor stabiliteit
    private static final int REPATH_COOLDOWN_TICKS = 20;

    private final BotEntity bot;

    private BlockPos storagePos;
    private String requestedItemId; // meestal *_planks
    private int requestedCount;
    private int repathCooldown = 0;

    public CrafterGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (bot.level().isClientSide) return false;
        if (bot.getJob() != BotJobType.CRAFTER) return false;
        if (!(bot.level() instanceof ServerLevel)) return false;

        VillageData vd = bot.getVillageData();
        if (vd == null || !vd.hasStoragePos()) return false;

        Optional<JobTicket> opt = bot.getActiveTicket();
        if (opt.isEmpty()) return false;

        JobTicket t = opt.get();
        if (t.getType() != BotJobType.CRAFTER) return false;

        this.storagePos = vd.getStoragePos();
        this.requestedItemId = t.hasRequestedItem() ? t.getRequestedItemId() : null;
        this.requestedCount = t.hasRequestedItem() ? t.getRequestedCount() : 0;

        return this.storagePos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        storagePos = null;
        requestedItemId = null;
        requestedCount = 0;
        bot.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(bot.level() instanceof ServerLevel level)) return;
        if (storagePos == null) return;

        double distSq = bot.distanceToSqr(
                storagePos.getX() + 0.5D,
                storagePos.getY() + 1.0D,
                storagePos.getZ() + 0.5D
        );

        if (distSq > 4.0D) {
            if (repathCooldown-- <= 0) {
                bot.getNavigation().moveTo(
                        storagePos.getX() + 0.5D,
                        storagePos.getY() + 1.0D,
                        storagePos.getZ() + 0.5D,
                        1.0D
                );
                repathCooldown = REPATH_COOLDOWN_TICKS;
            }
            return;
        }

        // Dichtbij genoeg -> craft in de chest
        UUID vid = bot.getVillageId();
        if (vid == null) {
            bot.failActiveTicket();
            return;
        }

        VillageManagerData mgr = VillageManagerData.get(level);
        SettlementState ledger = mgr.getState(vid);

        // Als ticket specifiek planks vraagt en ledger heeft genoeg -> klaar
        if (requestedItemId != null && requestedCount > 0) {
            if (ledger.getCount(requestedItemId) >= requestedCount) {
                bot.completeActiveTicket();
                return;
            }
        }

        BlockEntity be = level.getBlockEntity(storagePos);
        if (!(be instanceof Container container)) {
            bot.failActiveTicket();
            return;
        }

        int craftsDone = 0;

        for (int n = 0; n < CRAFTS_PER_TICK; n++) {
            CraftPlan plan = findLogToCraft(container);
            if (plan == null) {
                // geen logs beschikbaar
                bot.completeActiveTicket();
                return;
            }

            // 1 log eruit
            int removed = removeFromContainer(container, plan.logItem, 1);
            if (removed <= 0) {
                bot.completeActiveTicket();
                return;
            }

            // 4 planks erin (als past)
            int produced = 4;
            Item plankItem = plan.plankItem;
            int inserted = addToContainer(container, new ItemStack(plankItem, produced));
            int actuallyInserted = produced - inserted; // addToContainer retourneert remainder count

            // Als niets past: log terug geven (safety) en stoppen
            if (actuallyInserted <= 0) {
                addToContainer(container, new ItemStack(plan.logItem, 1));
                bot.completeActiveTicket();
                return;
            }

            // Ledger sync: consume 1 log, add planks die echt geplaatst zijn
            String logId = BuiltInRegistries.ITEM.getKey(plan.logItem).toString();
            String plankId = BuiltInRegistries.ITEM.getKey(plankItem).toString();

            // consume log
            if (!ledger.tryConsume(logId, 1)) {
                // Ledger mismatch -> corrigeer door log terug in chest te stoppen en stoppen
                addToContainer(container, new ItemStack(plan.logItem, 1));
                bot.failActiveTicket();
                return;
            }

            ledger.add(plankId, actuallyInserted);

            mgr.setDirty();
            craftsDone++;
        }

        if (craftsDone > 0) {
            // Blijf nog even doorcraften volgende ticks totdat ticket voldaan is
        }
    }

    private static class CraftPlan {
        final Item logItem;
        final Item plankItem;

        CraftPlan(Item logItem, Item plankItem) {
            this.logItem = logItem;
            this.plankItem = plankItem;
        }
    }

    /**
     * Zoek een log in de chest en bepaal bijpassende planks.
     * mapping: <wood>_log -> <wood>_planks
     */
    private static CraftPlan findLogToCraft(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack st = container.getItem(i);
            if (st.isEmpty()) continue;

            Item item = st.getItem();
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) continue;

            String path = key.getPath();
            if (!path.endsWith("_log")) continue;

            String plankPath = path.substring(0, path.length() - "_log".length()) + "_planks";
            ResourceLocation plankKey = new ResourceLocation(key.getNamespace(), plankPath);

            Item plankItem = BuiltInRegistries.ITEM.get(plankKey);
            if (plankItem == Items.AIR) continue;

            return new CraftPlan(item, plankItem);
        }
        return null;
    }

    private static int removeFromContainer(Container c, Item item, int count) {
        int remaining = count;

        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty()) continue;
            if (st.getItem() != item) continue;

            int take = Math.min(remaining, st.getCount());
            st.shrink(take);
            remaining -= take;
            if (st.isEmpty()) c.setItem(i, ItemStack.EMPTY);

            if (remaining <= 0) break;
        }

        return count - remaining;
    }

    /**
     * Voeg stack toe. Retourneert hoeveel er NIET paste (remainder count).
     */
    private static int addToContainer(Container c, ItemStack stack) {
        if (stack.isEmpty()) return 0;

        ItemStack remaining = stack.copy();

        // stacken
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack slot = c.getItem(i);
            if (slot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameTags(slot, remaining)) continue;

            int max = Math.min(slot.getMaxStackSize(), c.getMaxStackSize());
            int space = max - slot.getCount();
            if (space <= 0) continue;

            int toAdd = Math.min(space, remaining.getCount());
            slot.grow(toAdd);
            remaining.shrink(toAdd);
            if (remaining.isEmpty()) return 0;
        }

        // lege slot
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack slot = c.getItem(i);
            if (!slot.isEmpty()) continue;

            c.setItem(i, remaining);
            return 0;
        }

        return remaining.getCount();
    }
}
