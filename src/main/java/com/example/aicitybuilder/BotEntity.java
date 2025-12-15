package com.example.aicitybuilder;

import com.example.aicitybuilder.building.Blueprints;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.item.Items;

/**
 * Humanoid bot entity met survival-progressie richting diamond gear.
 *
 * Realistische boom-hak AI:
 *  - herkent echte bomen (stam + leaves)
 *  - loopt naar de stam-basis
 *  - hakt de stam van onder naar boven
 *  - springt / plaatst blocs als het iets niet kan bereiken
 *  - raapt drops (logs etc.) op rond de boom
 */
public class BotEntity extends PathfinderMob {

    public enum ProgressionStage {
        COLLECT_WOOD,
        COLLECT_STONE,
        COLLECT_IRON,
        COLLECT_DIAMOND,
        DONE
    }

    @Nullable
    private UUID ownerUuid;

    // Simpele job / rol voor de bot (voor toekomstig dorpsgedrag)
    private static int JOB_COUNTER = 0;
    private BotJobType job = BotJobType.LUMBERJACK;
    private boolean jobAssigned = false;

    // Millénaire-like citizen state
    @Nullable
    private UUID activeTicketId;
    @Nullable
    private BlockPos homePos;

    private ProgressionStage stage = ProgressionStage.COLLECT_WOOD;

    // Eenvoudige progressie tellers (momenteel niet in gebruik)
    int woodCount = 0;
    int stoneCount = 0;
    int ironCount = 0;
    int diamondCount = 0;

    public BotJobType getJob() {
        return job;
    }

    public void setJob(BotJobType job) {
        this.job = job;
    }

    @Nullable
    public UUID getActiveTicketId() {
        return activeTicketId;
    }

    public void setActiveTicketId(UUID id) {
        this.activeTicketId = id;
    }

    public void clearActiveTicket() {
        this.activeTicketId = null;
    }

    @Nullable
    public BlockPos getHomePos() {
        return homePos;
    }

    public void setHomePos(BlockPos homePos) {
        this.homePos = homePos;
    }

    public java.util.Optional<com.example.aicitybuilder.village.JobTicket> getActiveTicket() {
        UUID id = this.activeTicketId;
        if (id == null) return java.util.Optional.empty();
        VillageData vd = getVillageData();
        if (vd == null) return java.util.Optional.empty();
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

    /**
     * Haal de VillageData op voor deze wereld (alleen serverkant).
     */
    public VillageData getVillageData() {
        if (!this.level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
            return VillageData.get(serverLevel);
        }
        return null;
    }

    /**
     * Zorg dat er ten minste één village-center is ingesteld.
     * Voor nu gebruikt de eerste bot zijn huidige positie als center.
     */
    public void ensureVillageInitialized() {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        VillageData data = VillageData.get(serverLevel);
        if (!data.hasCenter()) {
            data.setCenter(this.blockPosition());
        }

        // Default home: village center (of spawn-pos als center nog niet bestond)
        if (this.homePos == null) {
            this.homePos = data.getCenter();
        }
        // Zorg ook dat er een opslag-huis bij het dorpcenter wordt gegenereerd
        VillageStructures.ensureStorageHouse(serverLevel, data);
    }
    /**
     * Zorgt dat nieuwe bots automatisch een job krijgen, eerlijk verdeeld over LUMBERJACK en BUILDER
     * binnen het dorp (gebaseerd op VillageData-center en -radius).
     */
    /**
     * Eenvoudige globale taakverdeling: bots krijgen om-en-om LUMBERJACK en BUILDER.
     * Dit maakt het gedrag voorspelbaar en makkelijk te testen.
     */
    private void ensureJobAssigned() {
        if (this.jobAssigned) {
            return;
        }
        if (this.level().isClientSide) {
            return;
        }

        int index = JOB_COUNTER++;
        // Basis verdeling: 1 lumberjack, 1 hauler, 1 builder, repeat
        int slot = Math.floorMod(index, 3);
        BotJobType chosen = switch (slot) {
            case 0 -> BotJobType.LUMBERJACK;
            case 1 -> BotJobType.HAULER;
            default -> BotJobType.BUILDER;
        };
        this.setJob(chosen);
        this.jobAssigned = true;
    }




    // Echte inventory voor de bot, vergelijkbaar met een speler (36 slots)
    private final SimpleContainer inventory = new SimpleContainer(36);
    public SimpleContainer getInventory() { return inventory; }
    private int storageTickCounter = 0;


    private int buildCooldown = 0;
    protected BotEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(true);

        // Prevent vanilla despawning when the bot is far away from players.
        // These bots are village citizens and should persist like villagers.
        this.setPersistenceRequired();
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        // Never despawn due to distance.
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Alleen inventory openen als speler sneakt (Shift + Rechtsklik)
        if (player.isShiftKeyDown()) {
            if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(new MenuProvider() {
                    @Override
                    public Component getDisplayName() {
                        return Component.literal("Bot Inventory");
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
        // Dag/nacht routine
        this.goalSelector.addGoal(0, new GoHomeAtNightGoal(this));

        // Dispatcher: claim job tickets
        this.goalSelector.addGoal(1, new ClaimJobGoal(this));

        // Ticket-driven jobs
        this.goalSelector.addGoal(2, new LumberjackGoal(this));
        this.goalSelector.addGoal(3, new HaulItemGoal(this));

        // Overige jobs (nog niet ticket-driven)
        this.goalSelector.addGoal(4, new BuilderGoal(this));

        // Storage logic (inventory legen)
        this.goalSelector.addGoal(5, new DepositToStorageGoal(this));

        // Basis gedrag
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }


    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D);
    }

    public void setOwner(ServerPlayer player) {
        this.ownerUuid = player.getUUID();
    }

    @Nullable
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public ProgressionStage getStage() {
        return stage;
    }

    public void setStage(ProgressionStage stage) {
        this.stage = stage;
    }

    public void addWood(int amount) {
        this.woodCount += amount;
    }

    public void addStone(int amount) {
        this.stoneCount += amount;
    }

    public void addIron(int amount) {
        this.ironCount += amount;
    }

    public void addDiamond(int amount) {
        this.diamondCount += amount;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerUuid != null) {
            tag.putUUID("Owner", ownerUuid);
        }
        tag.putString("Job", job.name());
        tag.putBoolean("JobAssigned", jobAssigned);
        tag.putInt("Stage", stage.ordinal());
        tag.putInt("Wood", woodCount);
        tag.putInt("Stone", stoneCount);
        tag.putInt("Iron", ironCount);
        tag.putInt("Diamond", diamondCount);

        if (activeTicketId != null) {
            tag.putUUID("ActiveTicket", activeTicketId);
        }
        if (homePos != null) {
            CompoundTag hp = new CompoundTag();
            hp.putInt("X", homePos.getX());
            hp.putInt("Y", homePos.getY());
            hp.putInt("Z", homePos.getZ());
            tag.put("HomePos", hp);
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
     * Basis-logica voor interactie met het dorpsopslagpunt (chest).
     * - wordt periodiek aangeroepen vanuit tick()
     * - dumpt items naar de chest als de inventory bijna vol is.
     */
    private void handleStorageLogic() {
        storageTickCounter++;
        // ongeveer 1x per seconde
        if (storageTickCounter < 20) {
            return;
        }
        storageTickCounter = 0;

        VillageData data = this.getVillageData();
        if (data == null || !data.hasStoragePos()) {
            return;
        }

        // Als inventory bijna vol is, probeer te lozen in de opslag-chest
        if (isInventoryAlmostFull()) {
            depositInventoryToChest(data.getStoragePos());
        }
    }
/**
 * MVP build logic:
 * - If VillageData has an active project, place 1 step every 10 ticks.
 */
private void handleBuildLogic() {
    if (!(this.level() instanceof ServerLevel)) {
        return;
    }
    ServerLevel serverLevel = (ServerLevel) this.level();

    // cooldown
    if (buildCooldown > 0) {
        buildCooldown--;
        return;
    }
    buildCooldown = 10; // 1 block per 10 ticks

    VillageData data = VillageData.get(serverLevel);
    if (!data.hasActiveProject()) {
        return;
    }

    java.util.List<Blueprints.BuildStep> steps = Blueprints.get(data.getProjectId());
    if (steps.isEmpty()) {
        data.clearProject();
        return;
    }

    int i = data.getProjectStep();
    if (i >= steps.size()) {
        data.clearProject(); // klaar
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

    // AIR step: carve opening if needed
    if (target.isAir()) {
        if (!current.isAir()) {
            serverLevel.setBlock(placePos, target, 3);
        }
        data.advanceProjectStep();
        return;
    }

    // Place if empty; otherwise skip (later: smarter logic)
    if (current.isAir()) {
        serverLevel.setBlock(placePos, target, 3);
    }
    data.advanceProjectStep();
}

    public boolean isInventoryAlmostFull() {
        int filled = 0;
        int size = inventory.getContainerSize();
        for (int i = 0; i < size; i++) {
            if (!inventory.getItem(i).isEmpty()) {
                filled++;
            }
        }
        // Laat een paar slots vrij voor nieuwe loot
        return filled >= size - 2;
    }

    /**
     * Schuift alle items uit de bot-inventory naar de chest op storagePos,
     * voor zover daar ruimte is.
     */
    private void depositInventoryToChest(BlockPos storagePos) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockEntity be = serverLevel.getBlockEntity(storagePos);
        if (!(be instanceof Container container)) {
            return;
        }

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack toMove = stack.copy();
            ItemStack remaining = addToContainer(container, toMove);
            if (remaining.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            } else {
                inventory.setItem(i, remaining);
            }
        }

        if (be instanceof ChestBlockEntity chest) {
            chest.setChanged();
        }
    }

    /**
     * Probeer een stack toe te voegen aan een willekeurige Container (bijv. chest).
     * Retourneert wat niet paste.
     */
    private ItemStack addToContainer(Container container, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack remaining = stack.copy();

        // Eerst stapelen op bestaande gelijksoortige stacks
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, remaining)) {
                int space = Math.min(slot.getMaxStackSize(), container.getMaxStackSize()) - slot.getCount();
                if (space > 0) {
                    int toAdd = Math.min(space, remaining.getCount());
                    slot.grow(toAdd);
                    remaining.shrink(toAdd);
                    if (remaining.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                }
            }
        }

        // Dan zoeken naar een lege slot
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, remaining);
                return ItemStack.EMPTY;
            }
        }

        return remaining;
    }

    /**
     * Verbruikt (1) blok uit de bot-inventory dat overeenkomt met de gegeven blockstate.
     * Gebruikt het item van het block (bijv. planks/logs). Retourneert true als het gelukt is.
     */
    public boolean consumeBlockFromInventory(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        ItemStack template = new ItemStack(state.getBlock().asItem());
        if (template.isEmpty()) {
            return false;
        }

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, template)) {
                slot.shrink(1);
                if (slot.isEmpty()) {
                    inventory.setItem(i, ItemStack.EMPTY);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Probeert een aantal blokken van een bepaald type uit de dorpsopslag te halen
     * en in de bot-inventory te stoppen.
     *
     * @param state      blockstate waarvan het item gepakt moet worden
     * @param maxAmount  maximum aantal items om te pakken
     * @return true als er daadwerkelijk iets uit de chest is gehaald
     */

    /**
     * Probeert, specifiek voor plank-blokken, eerst planks in de inventory te krijgen
     * door logs te "craften". Dit is een simpele simulatie van craften aan een crafting table:
     * - kies bijpassende log-soort voor de gevraagde planks
     * - haal zo nodig logs uit de dorpsopslag
     * - converteer logs -> planks (4 planks per log)
     *
     * @param neededPlanks blockstate van de plank die nodig is (bijv. OAK_PLANKS)
     * @param minPlanks    minimaal aantal planks dat we na afloop willen hebben
     * @return true als er planks gecraft/georganiseerd zijn (inventory bevat nu planks)
     */
    public boolean tryCraftPlanksFor(BlockState neededPlanks, int minPlanks) {
        // Sta ik wel bij de crafting table in de opslag?
        VillageData data = this.getVillageData();
        if (data == null || !data.hasStoragePos()) {
            return false;
        }
        BlockPos storagePos = data.getStoragePos();
        // In VillageStructures staat de crafting table 1 blok naast de chest in +X richting
        BlockPos craftPos = storagePos.offset(1, 0, 0);
        double craftDistSq = this.distanceToSqr(
                craftPos.getX() + 0.5D,
                craftPos.getY() + 0.5D,
                craftPos.getZ() + 0.5D
        );
        if (craftDistSq > 4.0D) { // verder dan ~2 blokken
            return false;
        }

        if (neededPlanks == null || !neededPlanks.is(BlockTags.PLANKS)) {
            return false;
        }

        Block logBlock = getLogBlockForPlanks(neededPlanks.getBlock());
        if (logBlock == null) {
            return false;
        }

        ItemStack plankTemplate = new ItemStack(neededPlanks.getBlock().asItem());
        if (plankTemplate.isEmpty()) {
            return false;
        }

        ItemStack logTemplate = new ItemStack(logBlock.asItem());
        if (logTemplate.isEmpty()) {
            return false;
        }

        int havePlanks = countItemsInInventory(plankTemplate);
        if (havePlanks >= minPlanks) {
            return true;
        }

        int missing = minPlanks - havePlanks;
        int logsNeeded = (missing + 3) / 4; // 1 log -> 4 planks

        int logsAvailable = countItemsInInventory(logTemplate);

        if (logsAvailable < logsNeeded) {
            // Probeer extra logs uit dorpsopslag te trekken
            BlockState logState = logBlock.defaultBlockState();
            if (pullBlocksFromStorage(logState, logsNeeded - logsAvailable)) {
                logsAvailable = countItemsInInventory(logTemplate);
            }
        }

        if (logsAvailable <= 0) {
            return false;
        }

        int logsToUse = Math.min(logsAvailable, logsNeeded);
        int crafted = 0;

        // Verbruik logs uit de inventory
        for (int i = 0; i < inventory.getContainerSize() && logsToUse > 0; i++) {
            ItemStack slot = inventory.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, logTemplate)) {
                int use = Math.min(slot.getCount(), logsToUse);
                slot.shrink(use);
                if (slot.isEmpty()) {
                    inventory.setItem(i, ItemStack.EMPTY);
                }
                logsToUse -= use;
                crafted += use * 4;
            }
        }

        if (crafted <= 0) {
            return false;
        }

        // Voeg de gecrafte planks toe aan de inventory
        ItemStack planksStack = new ItemStack(neededPlanks.getBlock().asItem(), crafted);
        ItemStack leftover = inventory.addItem(planksStack);
        if (!leftover.isEmpty() && this.level() instanceof ServerLevel serverLevel) {
            // Als er niets meer in de inventory past, droppen we de rest in de wereld
            ItemEntity drop = new ItemEntity(serverLevel, this.getX(), this.getY(), this.getZ(), leftover);
            serverLevel.addFreshEntity(drop);
        }

        return true;
    }

    /**
     * Bepaalt, voor een gegeven plank-blok, welke log-blok daar logisch bij hoort.
     * Dit is een eenvoudige mapping voor de meest voorkomende houtsoorten.
     */
    @Nullable
    private Block getLogBlockForPlanks(Block planks) {
        if (planks == Blocks.OAK_PLANKS) return Blocks.OAK_LOG;
        if (planks == Blocks.SPRUCE_PLANKS) return Blocks.SPRUCE_LOG;
        if (planks == Blocks.BIRCH_PLANKS) return Blocks.BIRCH_LOG;
        if (planks == Blocks.JUNGLE_PLANKS) return Blocks.JUNGLE_LOG;
        if (planks == Blocks.ACACIA_PLANKS) return Blocks.ACACIA_LOG;
        if (planks == Blocks.DARK_OAK_PLANKS) return Blocks.DARK_OAK_LOG;
        if (planks == Blocks.MANGROVE_PLANKS) return Blocks.MANGROVE_LOG;
        if (planks == Blocks.CHERRY_PLANKS) return Blocks.CHERRY_LOG;
        if (planks == Blocks.BAMBOO_PLANKS) return Blocks.BAMBOO_BLOCK;
        // Fallback: geen bekende mapping
        return null;
    }

    /**
     * Telt hoeveel items van het gegeven type in de bot-inventory zitten.
     */
    private int countItemsInInventory(ItemStack template) {
        if (template.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, template)) {
                total += slot.getCount();
            }
        }
        return total;
    }

    public boolean pullBlocksFromStorage(BlockState state, int maxAmount) {
        if (state.isAir()) {
            return false;
        }
        VillageData data = this.getVillageData();
        if (data == null || !data.hasStoragePos()) {
            return false;
        }
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        BlockPos storagePos = data.getStoragePos();
        // Bot moet dicht bij de opslag-chest staan om blokken te pakken
        double storageDistSq = this.distanceToSqr(
                storagePos.getX() + 0.5D,
                storagePos.getY() + 0.5D,
                storagePos.getZ() + 0.5D
        );
        if (storageDistSq > 4.0D) { // verder dan ~2 blokken
            return false;
        }

        BlockEntity be = serverLevel.getBlockEntity(storagePos);
        if (!(be instanceof Container container)) {
            return false;
        }

        ItemStack template = new ItemStack(state.getBlock().asItem());
        if (template.isEmpty()) {
            return false;
        }

        int remaining = maxAmount;
        boolean tookAny = false;

        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, template)) {
                int toTake = Math.min(slot.getCount(), remaining);
                ItemStack extracted = slot.split(toTake);
                ItemStack leftover = inventory.addItem(extracted);
                int actuallyInserted = toTake - leftover.getCount();
                if (actuallyInserted > 0) {
                    tookAny = true;
                    remaining -= actuallyInserted;
                }
                // Als er iets overbleef dat niet paste, probeer het terug in de slot te doen
                if (!leftover.isEmpty()) {
                    slot.grow(leftover.getCount());
                    break;
                }
            }
        }

        if (be instanceof ChestBlockEntity chest) {
            chest.setChanged();
        }

        return tookAny;
    }

    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        // Schrijf logs direct weg naar VillageData (dorpseconomie)
        if (!this.level().isClientSide) {
            VillageData data = this.getVillageData();
            if (data != null && stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (block.defaultBlockState().is(BlockTags.LOGS)) {
                    int amount = stack.getCount();
                    ResourceLocation key = stack.getItem().builtInRegistryHolder().key().location();
                    data.addResource(key.toString(), amount);
                }
            }
        }
        // Probeer item in de interne inventory te stoppen
        ItemStack remaining = inventory.addItem(stack);
        if (remaining.isEmpty()) {
            // Alles paste, item entity verwijderen
            itemEntity.discard();
        } else {
            // Niet alles paste, update de entity met wat over is
            itemEntity.setItem(remaining);
        }
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
        // Drop alle items uit de bot-inventory in de wereld, zoals een speler
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(stack);
            }
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            ownerUuid = tag.getUUID("Owner");
        }
        if (tag.contains("Job")) {
            try {
                this.job = BotJobType.valueOf(tag.getString("Job"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (tag.contains("JobAssigned")) {
            this.jobAssigned = tag.getBoolean("JobAssigned");
        }


        if (tag.contains("Stage")) {
            int ord = tag.getInt("Stage");
            if (ord >= 0 && ord < ProgressionStage.values().length) {
                stage = ProgressionStage.values()[ord];
            }
        }
        woodCount = tag.getInt("Wood");
        stoneCount = tag.getInt("Stone");
        ironCount = tag.getInt("Iron");
        diamondCount = tag.getInt("Diamond");

        if (tag.hasUUID("ActiveTicket")) {
            activeTicketId = tag.getUUID("ActiveTicket");
        }
        if (tag.contains("HomePos")) {
            CompoundTag hp = tag.getCompound("HomePos");
            homePos = new BlockPos(hp.getInt("X"), hp.getInt("Y"), hp.getInt("Z"));
        }
    }

    public static class TreeChopGoal extends Goal {

        private final BotEntity bot;
        private BlockPos targetLog;
        private BlockPos treeBase;
        private final java.util.List<BlockPos> treeLogs = new ArrayList<>();
        private final java.util.List<BlockPos> scaffoldBlocks = new ArrayList<>();
        private int chopTick;

        // Vastzitten-detectie
        private Vec3 lastPos;
        private int stuckTicks;
        private static final double STUCK_DISTANCE_EPSILON = 0.01D; // hoe weinig beweging = "stil"
        private static final int STUCK_TICKS_THRESHOLD = 40;        // na 40 ticks vast = nieuw pad (≈ 2 sec)

        public TreeChopGoal(BotEntity bot) {
            this.bot = bot;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (bot.level().isClientSide) return false;
            // Alleen actief voor lumberjack-job
            if (bot.getJob() != BotJobType.LUMBERJACK) return false;
            // Vereist village data (stadssysteem actief)
            if (bot.getVillageData() == null) return false;

            // Zoek dichtstbijzijnde log in een kleine radius
            BlockPos nearest = findNearestLog(8);
            if (nearest != null) {
                this.targetLog = nearest;
                return true;
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            // Als alle logs weg zijn, blijven we zolang er nog scaffold of loot is
            if (treeBase != null && treeLogs.isEmpty()) {
                if (!scaffoldBlocks.isEmpty()) return true;

                AABB box = new AABB(treeBase).inflate(8.0D, 3.0D, 8.0D);
                java.util.List<ItemEntity> items =
                        bot.level().getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive());
                return !items.isEmpty();
            }
            return targetLog != null && !treeLogs.isEmpty();
        }

        @Override
        public void start() {
            this.chopTick = 0;
            this.treeLogs.clear();
            this.scaffoldBlocks.clear();
            this.treeBase = null;

            this.lastPos = null;
            this.stuckTicks = 0;

            if (targetLog == null) return;

            BlockPos base = getTreeBase(targetLog);
            this.treeBase = base;

            BlockPos cursor = base;
            int max = 32;

            // Verzamel alle stam-logs van onder naar boven
            for (int i = 0; i < max; i++) {
                BlockState state = bot.level().getBlockState(cursor);
                if (!state.is(BlockTags.LOGS)) break;
                treeLogs.add(cursor.immutable());
                cursor = cursor.above();
            }

            if (treeLogs.isEmpty()) {
                targetLog = null;
            }
        }

        @Override
        public void stop() {
            this.targetLog = null;
            this.treeBase = null;
            this.treeLogs.clear();
            this.scaffoldBlocks.clear();
            this.chopTick = 0;

            this.lastPos = null;
            this.stuckTicks = 0;
        }

        @Override
        public void tick() {
            // ---- FASE 2: boom is weg, nu eerst pilaren opruimen, dan loot pakken ----
            if (treeLogs.isEmpty()) {
                if (treeBase == null) {
                    stop();
                    return;
                }

                // 1) Scaffold opruimen (tijdelijke blokken die hij geplaatst heeft)
                if (!scaffoldBlocks.isEmpty()) {
                    BlockPos p = scaffoldBlocks.remove(0);
                    BlockState s = bot.level().getBlockState(p);
                    if (!s.isAir()) {
                        bot.level().destroyBlock(p, false, bot);
                    }
                    // tijdens opruimen niet aan stuck-check doen, gebeurt lokaal
                    return; // deze tick alleen opruimen
                }

                // 2) Loot zoeken in de buurt van de bot
                AABB box = new AABB(treeBase).inflate(8.0D, 3.0D, 8.0D);
                java.util.List<ItemEntity> items =
                        bot.level().getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive());

                if (items.isEmpty()) {
                    // Geen drops meer -> goal klaar
                    stop();
                    return;
                }

                // Dichtstbijzijnde item zoeken
                ItemEntity nearest = null;
                double bestDistSqr = Double.MAX_VALUE;
                for (ItemEntity item : items) {
                    double d = bot.distanceToSqr(item.getX(), item.getY(), item.getZ());
                    if (d < bestDistSqr) {
                        bestDistSqr = d;
                        nearest = item;
                    }
                }

                if (nearest != null) {
                    double dist = Math.sqrt(bestDistSqr);

                    // Probeer één leaf langs de lijn naar het item weg te slaan (realistisch tikken)
                    clearBlockingLeavesTowards(nearest.position());

                    // Niet dichtbij genoeg: loop er echt bovenop zodat hij het zeker oppakt
                    if (dist > 0.4D) {
                        Vec3 target = nearest.position();
                        bot.getNavigation().moveTo(
                                target.x,
                                target.y,
                                target.z,
                                1.1D
                        );
                        // vastzitten-check terwijl hij naar loot loopt
                        updateStuckCheck(target, 1.1D);
                    } else {
                        // dicht genoeg: geen stuck-check nodig
                        this.lastPos = null;
                        this.stuckTicks = 0;
                    }
                    // Als hij er vlakbij is (< ~1.5 blok), doet pickUpItem() de rest via collision
                }
                return;
            }

            // ---- FASE 1: er zijn nog logs om te hakken ----
            BlockPos logPos = treeLogs.get(0);
            BlockState state = bot.level().getBlockState(logPos);
            if (!state.is(BlockTags.LOGS)) {
                // Log is al weg, ga naar de volgende
                treeLogs.remove(0);
                return;
            }

            // Afstand tot huidige log
            double dx = (logPos.getX() + 0.5D) - bot.getX();
            double dz = (logPos.getZ() + 0.5D) - bot.getZ();
            double horizontalDistSq = dx * dx + dz * dz;
            double distSq = bot.distanceToSqr(
                    logPos.getX() + 0.5D,
                    logPos.getY() + 0.5D,
                    logPos.getZ() + 0.5D
            );

            // Hoeveel hoger is de log t.o.v. onze voeten?
            int botFeetY = bot.blockPosition().getY();
            int heightDiff = logPos.getY() - botFeetY;

            // Als we ongeveer onder de boom staan en de log duidelijk hoger is:
            if (horizontalDistSq <= 1.5D && heightDiff > 2) {
                // Zolang we NIET binnen 4.5 blok reach komen, proberen we 1 stap te pilaren.
                if (distSq > 20.25D || logPos.getY() > bot.getY() + 1.2D) {
                    if (tryBuildPillarStep(logPos)) {
                        // springen / pilaren is lokaal, reset stuck-check
                        this.lastPos = null;
                        this.stuckTicks = 0;
                        return; // deze tick alleen pilaren
                    } else {
                        // Als pilaren niet lukt, probeer één leaf in de kijkrichting weg te slaan
                        clearBlockingLeavesTowards(Vec3.atCenterOf(logPos));
                    }
                }
            }

            // Te ver: loop erheen
            if (distSq > 20.25D) { // verder dan 4.5 blokken (speler reach): loop erheen
                Vec3 center = Vec3.atCenterOf(logPos);
                bot.getNavigation().moveTo(center.x, center.y, center.z, 1.0D);
                // vastzitten-check terwijl hij naar de log loopt
                updateStuckCheck(center, 1.0D);
                return;
            }

            // Dichtbij genoeg: kijk naar het blok en hak periodiek
            Vec3 center = Vec3.atCenterOf(logPos);
            bot.getLookControl().setLookAt(center.x, center.y, center.z, 30.0F, 30.0F);

            // dichtbij = geen path meer nodig, stuck-check resetten
            this.lastPos = null;
            this.stuckTicks = 0;

            chopTick++;
            if (chopTick >= 10) { // elke ~0.5s een hak
                chopTick = 0;
                // Hak-animatie zoals een speler (forceer client update)
                bot.swing(InteractionHand.MAIN_HAND, true);

                // Blok breken: dropt normale loot (logs) die de bot daarna kan oppakken
                bot.level().destroyBlock(logPos, true, bot);

                // Ga naar volgende log in de stam
                treeLogs.remove(0);
            }
        }

        /**
         * Sla één leaf-blok weg in de richting van het doel (zoals een speler zou doen).
         */
        private void clearBlockingLeavesTowards(Vec3 target) {
            Vec3 start = new Vec3(bot.getX(), bot.getEyeY(), bot.getZ());
            Vec3 dir = target.subtract(start);
            double len = dir.length();
            if (len < 0.0001D) return;
            dir = dir.scale(1.0D / len);

            double maxDist = Math.min(len, 6.0D); // ongeveer speler-reach richting doel
            double step = 0.25D; // kwart blok stappen

            for (double d = 0.5D; d <= maxDist; d += step) {
                Vec3 sample = start.add(dir.scale(d));
                BlockPos p = BlockPos.containing(sample);
                BlockState s = bot.level().getBlockState(p);

                if (s.is(BlockTags.LEAVES)) {
                    bot.swing(InteractionHand.MAIN_HAND, true);
                    bot.level().destroyBlock(p, false, bot);
                    break; // maar één leaf per tick
                }

                if (!s.isAir() && !s.is(BlockTags.LEAVES)) {
                    // Iets massiefs tussen ons en het doel: ray stoppen
                    break;
                }
            }
        }

        /**
         * Probeer een blok te plaatsen zodat de bot 1 blok hoger komt,
         * gebruikmakend van een blok uit zijn inventory. We kiezen een positie
         * waar rond hoofd/borst voldoende lucht is, zodat hij niet vast of
         * gestikt raakt.
         */
        private boolean tryBuildPillarStep(BlockPos targetLog) {
            BlockPos feet = bot.blockPosition();
            java.util.List<BlockPos> candidates = new ArrayList<>();
            candidates.add(feet);
            candidates.add(feet.east());
            candidates.add(feet.west());
            candidates.add(feet.north());
            candidates.add(feet.south());

            // dichtst bij de stam eerst
            candidates.sort((a, b) -> Double.compare(a.distSqr(targetLog), b.distSqr(targetLog)));

            int slot = findScaffoldSlot();
            if (slot == -1) {
                return false; // geen blokken om mee te pilaren
            }

            ItemStack stack = bot.inventory.getItem(slot);
            Block baseBlock = Block.byItem(stack.getItem());
            if (baseBlock == null) {
                return false;
            }

            for (BlockPos candidateFeet : candidates) {
                BlockPos below = candidateFeet.below();
                BlockPos headPos = candidateFeet.above();

                BlockState belowState = bot.level().getBlockState(below);
                BlockState feetState = bot.level().getBlockState(candidateFeet);
                BlockState headState = bot.level().getBlockState(headPos);

                // We willen een stevige ondergrond en een vrije kolom voor de bot
                if (belowState.isAir()) continue;
                if (!feetState.isAir()) continue;
                if (!headState.isAir()) continue;

                BlockState placeState = baseBlock.defaultBlockState();
                if (!placeState.canSurvive(bot.level(), candidateFeet)) {
                    continue;
                }

                // Verbruik 1 blok uit de stack
                stack.shrink(1);
                if (stack.isEmpty()) {
                    bot.inventory.setItem(slot, ItemStack.EMPTY);
                }

                // Blok plaatsen op de voeten-plek van de kandidaat
                bot.level().setBlock(candidateFeet, placeState, 3);
                scaffoldBlocks.add(candidateFeet.immutable());

                // Bot naar nieuwe positie verplaatsen (1 blok hoger)
                bot.setPos(
                        candidateFeet.getX() + 0.5D,
                        candidateFeet.getY() + 1.0D,
                        candidateFeet.getZ() + 0.5D
                );

                return true;
            }

            return false;
        }

        /**
         * Zoek een geschikt scaffold-blok in de inventory van de bot.
         */
        private int findScaffoldSlot() {
            for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
                ItemStack stack = bot.inventory.getItem(i);
                if (stack.isEmpty()) continue;

                Block b = Block.byItem(stack.getItem());
                if (b == null) continue;

                // simpele selectie: dirt / cobble / planks / logs, etc.
                if (b == Blocks.DIRT
                        || b == Blocks.COBBLESTONE
                        || b == Blocks.STONE
                        || b == Blocks.OAK_PLANKS
                        || b == Blocks.SPRUCE_PLANKS
                        || b == Blocks.BIRCH_PLANKS
                        || b == Blocks.JUNGLE_PLANKS
                        || b == Blocks.ACACIA_PLANKS
                        || b == Blocks.DARK_OAK_PLANKS
                        || b == Blocks.MANGROVE_PLANKS
                        || b == Blocks.CHERRY_PLANKS
                        || b == Blocks.OAK_LOG
                        || b == Blocks.SPRUCE_LOG
                        || b == Blocks.BIRCH_LOG
                        || b == Blocks.JUNGLE_LOG
                        || b == Blocks.ACACIA_LOG
                        || b == Blocks.DARK_OAK_LOG
                        || b == Blocks.MANGROVE_LOG
                        || b == Blocks.CHERRY_LOG) {
                    return i;
                }
            }
            return -1;
        }

        private BlockPos findNearestLog(int radius) {
            BlockPos origin = bot.blockPosition();
            BlockPos bestBase = null;
            double bestDist = Double.MAX_VALUE;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -1; dy <= 6; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = origin.offset(dx, dy, dz);
                        BlockState state = bot.level().getBlockState(pos);
                        if (!state.is(BlockTags.LOGS)) continue;

                        // Bepaal de stam-basis en check of dit een natuurlijke boom is
                        BlockPos base = getTreeBase(pos);
                        if (!isNaturalTreeBase(base)) continue;

                        double dist = bot.distanceToSqr(
                                base.getX() + 0.5D,
                                base.getY() + 0.5D,
                                base.getZ() + 0.5D
                        );
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestBase = base.immutable();
                        }
                    }
                }
            }
            return bestBase;
        }

        /**
         * Heel simpele heuristiek om te bepalen of een stam-basis bij een echte boom hoort:
         *  - ondergrond onder de stam is "natuurlijk" (dirt/gras-achtig)
         *  - ergens boven de stam-basis zitten LEAVES blokken.
         * Hiermee negeren we logs in gebouwen (op planken/stone, zonder bladeren).
         */
        private boolean isNaturalTreeBase(BlockPos base) {
            BlockState below = bot.level().getBlockState(base.below());
            if (!(below.is(BlockTags.DIRT)
                    || below.is(Blocks.GRASS_BLOCK)
                    || below.is(Blocks.DIRT)
                    || below.is(Blocks.PODZOL)
                    || below.is(Blocks.ROOTED_DIRT)
                    || below.is(Blocks.MYCELIUM))) {
                return false;
            }

            // Zoek bladeren in een kleine kolom/bundel boven de stam
            for (int dy = 1; dy <= 8; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos checkPos = base.offset(dx, dy, dz);
                        BlockState checkState = bot.level().getBlockState(checkPos);
                        if (checkState.is(BlockTags.LEAVES)) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        private BlockPos getTreeBase(BlockPos pos) {
            BlockPos base = pos;
            // Ga naar beneden zolang het nog logs zijn
            while (bot.level().getBlockState(base.below()).is(BlockTags.LOGS)) {
                base = base.below();
            }
            return base;
        }

        /**
         * Checkt of de bot vast zit. Als hij een tijdje nauwelijks beweegt
         * terwijl hij een path heeft, wordt het path gestopt en opnieuw gezet.
         */
        private void updateStuckCheck(Vec3 target, double speed) {
            // Als de navigation al klaar is, hoef je niets te checken
            if (bot.getNavigation().isDone()) {
                this.lastPos = null;
                this.stuckTicks = 0;
                return;
            }

            Vec3 current = bot.position();

            if (this.lastPos == null) {
                this.lastPos = current;
                this.stuckTicks = 0;
                return;
            }

            double distSq = current.distanceToSqr(this.lastPos);

            // Als we (bijna) niet bewegen, verhoog de stuck-teller
            if (distSq < STUCK_DISTANCE_EPSILON) {
                this.stuckTicks++;
                if (this.stuckTicks > STUCK_TICKS_THRESHOLD) {
                    onStuck(target, speed);
                }
            } else {
                // We bewegen weer → reset
                this.stuckTicks = 0;
                this.lastPos = current;
            }
        }

        /**
         * Wordt aangeroepen als de bot als "vast" wordt beschouwd.
         * We stoppen het huidige pad en proberen opnieuw naar hetzelfde doel te pathfinden.
         */
        private void onStuck(Vec3 target, double speed) {
            this.stuckTicks = 0;
            this.lastPos = bot.position();

            bot.getNavigation().stop();

            // Klein random offsetje kan helpen als exact dezelfde positie steeds faalt
            double offsetX = (bot.getRandom().nextDouble() - 0.5D) * 0.5D;
            double offsetZ = (bot.getRandom().nextDouble() - 0.5D) * 0.5D;

            bot.getNavigation().moveTo(
                    target.x + offsetX,
                    target.y,
                    target.z + offsetZ,
                    speed
            );
        }
    }

}