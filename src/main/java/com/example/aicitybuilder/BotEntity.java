package com.example.aicitybuilder;

import com.example.aicitybuilder.building.Blueprints;
import com.example.aicitybuilder.settlement.SettlementState;
import com.example.aicitybuilder.village.JobTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BotEntity extends PathfinderMob {

    public enum ProgressionStage {
        COLLECT_WOOD,
        COLLECT_STONE,
        COLLECT_IRON,
        COLLECT_DIAMOND,
        DONE
    }

    @Nullable private UUID ownerUuid;

    private static int JOB_COUNTER = 0;
    private BotJobType job = BotJobType.LUMBERJACK;
    private boolean jobAssigned = false;

    @Nullable private UUID activeTicketId;
    @Nullable private UUID villageId;
    @Nullable private BlockPos homePos;

    private ProgressionStage stage = ProgressionStage.COLLECT_WOOD;

    int woodCount = 0;
    int stoneCount = 0;
    int ironCount = 0;
    int diamondCount = 0;

    private final SimpleContainer inventory = new SimpleContainer(36);

    private int storageTickCounter = 0;
    private int buildCooldown = 0;

    protected BotEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(true);
        this.setPersistenceRequired();
    }

    public BotJobType getJob() { return job; }
    public void setJob(BotJobType job) { this.job = job; }

    @Nullable public UUID getActiveTicketId() { return activeTicketId; }
    public void setActiveTicketId(UUID id) { this.activeTicketId = id; }
    public void clearActiveTicket() { this.activeTicketId = null; }

    @Nullable public UUID getVillageId() { return villageId; }

    @Nullable public BlockPos getHomePos() { return homePos; }
    public void setHomePos(BlockPos homePos) { this.homePos = homePos; }

    public SimpleContainer getInventory() { return inventory; }

    public Optional<JobTicket> getActiveTicket() {
        UUID id = this.activeTicketId;
        if (id == null) return Optional.empty();
        VillageData vd = getVillageData();
        if (vd == null) return Optional.empty();
        return vd.getJobBoard().getTicket(id);
    }

    public void completeActiveTicket() {
        VillageData vd = getVillageData();
        if (vd != null && activeTicketId != null) {
            vd.getJobBoard().complete(activeTicketId);
        }
        activeTicketId = null;
    }

    public void failActiveTicket() {
        VillageData vd = getVillageData();
        if (vd != null && activeTicketId != null) {
            vd.getJobBoard().fail(activeTicketId);
        }
        activeTicketId = null;
    }

    @Nullable
    public VillageData getVillageData() {
        if (!this.level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
            return VillageData.get(serverLevel);
        }
        return null;
    }

    public void ensureVillageInitialized() {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel serverLevel)) return;

        VillageManagerData manager = VillageManagerData.get(serverLevel);

        if (this.villageId == null) {
            Optional<VillageManagerData.VillageRecord> nearest =
                    manager.findNearest(serverLevel, this.blockPosition(), 256);

            if (nearest.isPresent()) {
                this.villageId = nearest.get().id;
            } else {
                VillageManagerData.VillageRecord created = manager.createVillage(serverLevel, this.blockPosition());
                this.villageId = created.id;
            }
        }

        VillageManagerData.VillageRecord rec = manager.getVillage(villageId).orElse(null);
        if (rec != null && this.homePos == null) this.homePos = rec.centerPos();

        VillageData data = VillageData.get(serverLevel);
        if (rec != null && !data.hasCenter()) data.setCenter(rec.centerPos());
        if (this.homePos == null && data.hasCenter()) this.homePos = data.getCenter();

        VillageStructures.ensureStorageHouse(serverLevel, data);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(new MenuProvider() {
                    @Override
                    public net.minecraft.network.chat.Component getDisplayName() {
                        return net.minecraft.network.chat.Component.literal("Bot Inventory");
                    }

                    @Override
                    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
                        return new BotInventoryMenu(id, playerInventory, inventory);
                    }
                });
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new GoHomeAtNightGoal(this));

        this.goalSelector.addGoal(1, new ClaimJobGoal(this));

        // Producer jobs
        this.goalSelector.addGoal(2, new LumberjackGoal(this));
        this.goalSelector.addGoal(3, new MinerGoal(this));
        this.goalSelector.addGoal(4, new CrafterGoal(this));

        // Transport + build
        this.goalSelector.addGoal(5, new HaulItemGoal(this));
        this.goalSelector.addGoal(6, new BuilderGoal(this));

        // Deposit items to stockpile
        this.goalSelector.addGoal(7, new DepositToStorageGoal(this));

        // Idle behavior
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D);
    }

    public void setOwner(ServerPlayer player) { this.ownerUuid = player.getUUID(); }
    @Nullable public UUID getOwnerUuid() { return ownerUuid; }

    public ProgressionStage getStage() { return stage; }
    public void setStage(ProgressionStage stage) { this.stage = stage; }

    public void addWood(int amount) { this.woodCount += amount; }
    public void addStone(int amount) { this.stoneCount += amount; }
    public void addIron(int amount) { this.ironCount += amount; }
    public void addDiamond(int amount) { this.diamondCount += amount; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        if (ownerUuid != null) tag.putUUID("Owner", ownerUuid);

        tag.putString("Job", job.name());
        tag.putBoolean("JobAssigned", jobAssigned);

        tag.putInt("Stage", stage.ordinal());
        tag.putInt("Wood", woodCount);
        tag.putInt("Stone", stoneCount);
        tag.putInt("Iron", ironCount);
        tag.putInt("Diamond", diamondCount);

        if (activeTicketId != null) tag.putUUID("ActiveTicket", activeTicketId);
        if (villageId != null) tag.putUUID("VillageId", villageId);

        if (homePos != null) {
            CompoundTag hp = new CompoundTag();
            hp.putInt("X", homePos.getX());
            hp.putInt("Y", homePos.getY());
            hp.putInt("Z", homePos.getZ());
            tag.put("HomePos", hp);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        if (tag.hasUUID("Owner")) ownerUuid = tag.getUUID("Owner");

        if (tag.contains("Job")) {
            try { job = BotJobType.valueOf(tag.getString("Job")); }
            catch (Exception ignored) {}
        }
        jobAssigned = tag.getBoolean("JobAssigned");

        int st = tag.getInt("Stage");
        if (st >= 0 && st < ProgressionStage.values().length) {
            stage = ProgressionStage.values()[st];
        }

        woodCount = tag.getInt("Wood");
        stoneCount = tag.getInt("Stone");
        ironCount = tag.getInt("Iron");
        diamondCount = tag.getInt("Diamond");

        if (tag.hasUUID("ActiveTicket")) this.activeTicketId = tag.getUUID("ActiveTicket");
        if (tag.hasUUID("VillageId")) this.villageId = tag.getUUID("VillageId");

        if (tag.contains("HomePos")) {
            CompoundTag hp = tag.getCompound("HomePos");
            homePos = new BlockPos(hp.getInt("X"), hp.getInt("Y"), hp.getInt("Z"));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            ensureVillageInitialized();
            ensureJobAssigned();
            handleStorageLogic();
            handleBuildLogic();
        }
    }

    /**
     * Job rotatie (nu ook CRAFTER erbij)
     * lumber -> haul -> miner -> crafter -> builder
     */
    private void ensureJobAssigned() {
        if (this.jobAssigned) return;
        if (this.level().isClientSide) return;

        int index = JOB_COUNTER++;

        int slot = Math.floorMod(index, 5);
        BotJobType chosen = switch (slot) {
            case 0 -> BotJobType.LUMBERJACK;
            case 1 -> BotJobType.HAULER;
            case 2 -> BotJobType.MINER;
            case 3 -> BotJobType.CRAFTER;
            default -> BotJobType.BUILDER;
        };

        this.setJob(chosen);
        this.jobAssigned = true;
    }

    private void handleStorageLogic() {
        storageTickCounter++;

        if (storageTickCounter < 20) return;
        storageTickCounter = 0;

        VillageData data = this.getVillageData();
        if (data == null || !data.hasStoragePos()) return;

        if (isInventoryAlmostFull()) {
            depositInventoryToChest(data.getStoragePos());
        }
    }

    private void handleBuildLogic() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        if (buildCooldown > 0) {
            buildCooldown--;
            return;
        }
        buildCooldown = 10;

        VillageData data = VillageData.get(serverLevel);
        if (!data.hasActiveProject()) return;

        List<Blueprints.BuildStep> steps = Blueprints.get(data.getProjectId());
        if (steps.isEmpty()) {
            data.clearProject();
            return;
        }

        int i = data.getProjectStep();
        if (i >= steps.size()) {
            data.clearProject();
            return;
        }

        BlockPos origin = data.getProjectOrigin();
        if (origin == null) {
            data.clearProject();
            return;
        }

        Blueprints.BuildStep step = steps.get(i);
        BlockPos placePos = origin.offset(step.offset());
        BlockState target = step.state();
        BlockState current = serverLevel.getBlockState(placePos);

        if (target.isAir()) {
            if (!current.isAir()) serverLevel.setBlock(placePos, target, 3);
            data.advanceProjectStep();
            return;
        }

        if (!current.isAir()) {
            data.advanceProjectStep();
            return;
        }

        Item costItem = target.getBlock().asItem();
        if (costItem != null && costItem != Items.AIR) {
            UUID vid = this.getVillageId();
            if (vid != null) {
                VillageManagerData manager = VillageManagerData.get(serverLevel);
                SettlementState ledger = manager.getState(vid);

                String itemId = BuiltInRegistries.ITEM.getKey(costItem).toString();
                if (!ledger.tryConsume(itemId, 1)) {
                    buildCooldown = 20;
                    return;
                }
                manager.setDirty();
            }
        }

        serverLevel.setBlock(placePos, target, 3);
        data.advanceProjectStep();
    }

    public boolean isInventoryAlmostFull() {
        int filled = 0;
        int size = inventory.getContainerSize();
        for (int i = 0; i < size; i++) if (!inventory.getItem(i).isEmpty()) filled++;
        return filled >= size - 2;
    }

    private void depositInventoryToChest(BlockPos storagePos) {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        BlockEntity be = serverLevel.getBlockEntity(storagePos);
        if (!(be instanceof Container container)) return;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            ItemStack toMove = stack.copy();
            ItemStack remaining = addToContainer(container, toMove);

            if (remaining.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
            else inventory.setItem(i, remaining);
        }

        if (be instanceof ChestBlockEntity chest) {
            chest.setChanged();
        }
    }

    private ItemStack addToContainer(Container container, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;

        ItemStack remaining = stack.copy();

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, remaining)) {
                int space = Math.min(slot.getMaxStackSize(), container.getMaxStackSize()) - slot.getCount();
                if (space > 0) {
                    int toAdd = Math.min(space, remaining.getCount());
                    slot.grow(toAdd);
                    remaining.shrink(toAdd);
                    if (remaining.isEmpty()) return ItemStack.EMPTY;
                }
            }
        }

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, remaining);
                return ItemStack.EMPTY;
            }
        }

        return remaining;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return super.hurt(source, amount);
    }
}
