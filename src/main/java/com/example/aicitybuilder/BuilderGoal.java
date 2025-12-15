package com.example.aicitybuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Eenvoudige Builder-AI:
 * - Alleen actief als de bot de job BUILDER heeft.
 * - Gebruikt VillageData om het dorpcenter te vinden.
 * - Probeert één simpele houten hut te bouwen naast het dorp.
 */
public class BuilderGoal extends Goal {

    private final BotEntity bot;
    
    private final List<BlockPlacement> plan = new ArrayList<>();
    private int currentIndex = 0;
    private BlockPos origin;

    private boolean building;

    // State: is de builder bezig om materiaal uit de opslag te halen?
    private boolean fetchingFromStorage;

    // State: wachten we expliciet op nieuwe resources in de opslag?
    private boolean waitingForResources;

    // Extra state voor betere navigatie
    private Vec3 lastPos;
    private int stuckTicks;
    private static final double STUCK_DISTANCE_EPSILON = 0.01D;
    private static final int STUCK_TICKS_THRESHOLD = 40;

    // Simpele registratie van steigerblokken die de builder plaatst
    private final java.util.List<BlockPos> scaffoldBlocks = new ArrayList<>();

    public BuilderGoal(BotEntity bot) {
        this.bot = bot;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        this.lastPos = null;
        this.stuckTicks = 0;
    }

    @Override
    public boolean canUse() {
        if (bot.level().isClientSide) return false;
        if (bot.getJob() != BotJobType.BUILDER) return false;

        VillageData village = bot.getVillageData();
        if (village == null || !village.hasCenter()) {
            return false;
        }

        // Init bouwplan als het nog leeg is
        if (plan.isEmpty()) {
            initPlan();
        }

        // Bepaal een bouworigin op een veilige plek rondom het dorpscenter
        this.origin = findBuildOrigin(village);

        // Als we momenteel expliciet wachten op nieuwe resources in de opslag,
        // start dan alleen als er nu daadwerkelijk items in de opslag liggen.
        if (this.waitingForResources) {
            if (!hasAnyStorageItems(village)) {
                return false;
            }
            // Er liggen nu weer items in de opslag, we mogen opnieuw bouwen.
            this.waitingForResources = false;
        }

        // Start altijd opnieuw bij het begin van het plan
        this.currentIndex = 0;

        // Als alles al gebouwd is, geen nieuwe run
        return currentIndex < plan.size();

    }

    @Override
    public boolean canContinueToUse() {
        return building && bot.getJob() == BotJobType.BUILDER;
    }

    @Override
    public void start() {
        this.building = true;
        this.fetchingFromStorage = false;
    }

    @Override
    public void stop() {
        this.building = false;
        this.fetchingFromStorage = false;
    }

    @Override
    public void tick() {
        if (!building || origin == null || plan.isEmpty()) {
            return;
        }

        // Kies altijd de laagste laag (dy) die nog niet correct is gebouwd
        BlockPlacement target = findNextPlacement();
        if (target == null) {
            // Alles is al gebouwd
            VillageData village = bot.getVillageData();
            if (village != null) {
                // Kies volgende build-locatie voor een nieuw gebouw.
                village.advanceNextBuildIndex();
            }
            this.building = false;
            return;
        }

        BlockPos worldPos = getWorldPosFor(target);

        double distSq = bot.distanceToSqr(
                worldPos.getX() + 0.5D,
                worldPos.getY() + 1.0D,
                worldPos.getZ() + 0.5D
        );

        // Navigatie: als we nog ver weg zijn en we NIET bezig zijn met materiaal halen uit storage,
        // loop dan gewoon naar de bouwlocatie met stuck-detectie.
        if (distSq > 4.0D && !this.fetchingFromStorage) {
            moveTowardsWithStuckCheck(worldPos, 1.0D);
            return;
        }

        // We zijn dichtbij genoeg bij de bouwlocatie (of we zijn bewust naar storage aan het lopen):
        // reset eventueel vastzitten-state wanneer we echt dichtbij de bouwlocatie zijn.
        if (distSq <= 4.0D) {
            this.lastPos = null;
            this.stuckTicks = 0;
        }

        // Dichtbij genoeg: block plaatsen, eventueel met opruimen van obstakels
        BlockState existing = bot.level().getBlockState(worldPos);

        // Als het blok al precies is wat het plan wil, niets doen
        if (existing.equals(target.state)) {
            return;
        }

        // Als het bouwplan hier lucht (leegte) wil, dan mag ALLES wat hier staat weg
        // (heuvel, boom, oude blokken), zodat de binnenkant/deur/opening vrij wordt.
        if (target.state.isAir()) {
            if (!existing.isAir()) {
                bot.level().destroyBlock(worldPos, true, bot);
            }
            // Niets te plaatsen (lucht), dus klaar
            return;
        }

        // Doel is een echt blok: eerst proberen materiaal te krijgen
        boolean hadBlock = bot.consumeBlockFromInventory(target.state);
        if (!hadBlock) {
            // Probeer de benodigde blokken eerst uit de opslag-chest te halen.
            // Pak direct een grotere batch zodat we niet steeds heen en weer hoeven te lopen.
            if (bot.pullBlocksFromStorage(target.state, 64)) {
                hadBlock = bot.consumeBlockFromInventory(target.state);
            }
        }

        // Als we planks nodig hebben maar geen blokken konden vinden,
        // probeer logs uit opslag te halen en deze tot planks te "craften".
        // Ook hier vragen we meteen een redelijke buffer aan planks.
        if (!hadBlock && isPlanks(target.state)) {
            if (bot.tryCraftPlanksFor(target.state, 32)) {
                hadBlock = bot.consumeBlockFromInventory(target.state);
            }
        }

        // Als we nu wél materiaal hebben, zijn we klaar met materiaal halen uit storage.
        if (hadBlock) {
            this.fetchingFromStorage = false;
        }

        if (!hadBlock) {
            // Geen materiaal beschikbaar na inventory, storage en eventueel craft-pogingen.
            // Kijk of we naar de opslag kunnen gaan om materiaal te halen.
            VillageData village = bot.getVillageData();
            if (village != null && village.hasStoragePos()) {
                BlockPos storagePos = village.getStoragePos();
                Vec3 storageCenter = Vec3.atCenterOf(storagePos);
                double distSqStorage = bot.distanceToSqr(storageCenter);
                if (distSqStorage > 4.0D) {
                    // We staan niet bij de opslag: loop erheen om materiaal te halen.
                    this.fetchingFromStorage = true;
                    bot.getNavigation().moveTo(storageCenter.x, storageCenter.y, storageCenter.z, 1.0D);
                    // TODO: optional stuck-check richting opslag (niet geïmplementeerd).
                    return;
                } else {
                    // We staan al bij de opslag en hebben nog steeds geen bruikbaar materiaal:
                    // ga in idle (bouw-goal stoppen) tot er later weer resources zijn.
                    this.building = false;
                    this.fetchingFromStorage = false;
                    this.waitingForResources = true;
                    return;
                }
            } else {
                // Geen opslag bekend: ga in idle-modus en stop met bouwen.
                this.building = false;
                this.fetchingFromStorage = false;
                this.waitingForResources = true;
                return;
            }
        }

        // We hebben materiaal: nu blokken die in de weg staan opruimen.
        // Het maakt hier niet uit wat voor blok het is (terrain of oude structuur):
        // alles wat niet lucht is en niet hetzelfde block-type is als het doel mag weg.
        existing = bot.level().getBlockState(worldPos);
        if (!existing.isAir() && !existing.is(target.state.getBlock())) {
            bot.level().destroyBlock(worldPos, true, bot);
        }

        bot.level().setBlock(worldPos, target.state, 3);
    }

    /**
     * Zoek de eerstvolgende bouw-stap, waarbij we altijd de laagste dy
     * (laagste "laag") nemen die nog niet correct gebouwd is.
     */
    private BlockPlacement findNextPlacement() {
        if (origin == null || plan.isEmpty()) {
            return null;
        }

        BlockPlacement best = null;
        int bestDy = Integer.MAX_VALUE;

        for (BlockPlacement bp : plan) {
            BlockPos pos = getWorldPosFor(bp);
            BlockState existing = bot.level().getBlockState(pos);
            if (existing.equals(bp.state)) {
                continue; // al goed gebouwd
            }
            if (bp.dy < bestDy) {
                bestDy = bp.dy;
                best = bp;
            }
        }

        return best;
    }

    /**
     * Berekent de wereldpositie voor een BlockPlacement op basis van origin.
     * Het huis blijft vlak: de hele vloer gebruikt origin.getY() als basis.
     */
    private BlockPos getWorldPosFor(BlockPlacement target) {
        int baseX = origin.getX() + target.dx;
        int baseZ = origin.getZ() + target.dz;
        int worldY = origin.getY() + target.dy;
        return new BlockPos(baseX, worldY, baseZ);
    }

    /**
     * Navigatie-helper met simpele vastzitten-detectie zoals bij de houthakker.
     */
    private void moveTowardsWithStuckCheck(BlockPos targetPos, double speed) {
        Vec3 target = new Vec3(
                targetPos.getX() + 0.5D,
                targetPos.getY() + 1.0D,
                targetPos.getZ() + 0.5D
        );

        // Als de navigation al klaar is, reset stuck-state
        if (bot.getNavigation().isDone()) {
            this.lastPos = null;
            this.stuckTicks = 0;
        }

        bot.getNavigation().moveTo(target.x, target.y, target.z, speed);

        // Eenvoudige stuck-check: vergelijk huidige positie met vorige
        Vec3 current = bot.position();
        if (this.lastPos != null) {
            double distSq = current.distanceToSqr(this.lastPos);
            if (distSq < STUCK_DISTANCE_EPSILON) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }

            if (stuckTicks > STUCK_TICKS_THRESHOLD) {
                // Forceer een nieuw path naar hetzelfde doel
                bot.getNavigation().stop();
                bot.getNavigation().moveTo(target.x, target.y, target.z, speed);
                stuckTicks = 0;
            }
        }
        this.lastPos = current;
    }

    /**
     * Probeer een simpele steiger-stap te maken zodat de builder hoger kan komen.
     * We zoeken een blok rond de bot waar we een blok onder de voeten kunnen plaatsen
     * en waar hoofd/borst vrij zijn.
     */
    private boolean tryBuildScaffoldStep(BlockPos targetPos) {
        BlockPos feet = bot.blockPosition();
        java.util.List<BlockPos> candidates = new ArrayList<>();
        candidates.add(feet);
        candidates.add(feet.east());
        candidates.add(feet.west());
        candidates.add(feet.north());
        candidates.add(feet.south());

        for (BlockPos candidateFeet : candidates) {
            BlockPos below = candidateFeet.below();
            BlockPos headPos = candidateFeet.above();

            BlockState belowState = bot.level().getBlockState(below);
            BlockState feetState = bot.level().getBlockState(candidateFeet);
            BlockState headState = bot.level().getBlockState(headPos);

            // stevige ondergrond en vrije kolom voor de bot
            if (belowState.isAir()) continue;
            if (!feetState.isAir()) continue;
            if (!headState.isAir()) continue;

            // Gebruik dezelfde blokken als voor de hut (hout) als steiger-materiaal
            BlockState scaffoldState = Blocks.OAK_PLANKS.defaultBlockState();
            if (!scaffoldState.canSurvive(bot.level(), candidateFeet)) {
                continue;
            }

            // Verbruik 1 blok uit de inventory; als dat niet lukt, probeer uit opslag te trekken
            if (!bot.consumeBlockFromInventory(scaffoldState)) {
                if (bot.pullBlocksFromStorage(scaffoldState, 8)) {
                    if (!bot.consumeBlockFromInventory(scaffoldState)) {
                        continue;
                    }
                } else {
                    continue;
                }
            }

            // Blok plaatsen en bot omhoog zetten
            bot.level().setBlock(candidateFeet, scaffoldState, 3);
            scaffoldBlocks.add(candidateFeet.immutable());

            bot.setPos(
                    candidateFeet.getX() + 0.5D,
                    candidateFeet.getY() + 1.0D,
                    candidateFeet.getZ() + 0.5D
            );

            // Laat de bot naar het doel kijken voor wat extra "menselijk" gedrag
            Vec3 center = new Vec3(
                    targetPos.getX() + 0.5D,
                    targetPos.getY() + 1.0D,
                    targetPos.getZ() + 0.5D
            );
            bot.getLookControl().setLookAt(center.x, center.y, center.z, 30.0F, 30.0F);
            bot.swing(InteractionHand.MAIN_HAND, true);

            return true;
        }

        return false;
    }

    /**
     * Initialiseert een eenvoudig 5x5 hutje:
     * - vloer van planks (5x5)
     * - 3 blokken hoge muren
     * - simpel vlak dak.
     */
    private void initPlan() {
        plan.clear();
        BlockState floor = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState wall = Blocks.OAK_LOG.defaultBlockState();
        BlockState roof = Blocks.OAK_PLANKS.defaultBlockState();

        int size = 5;

        // Vloer (y=0)
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                plan.add(new BlockPlacement(x, 0, z, floor));
            }
        }

        // Muren (rand, hoogte 3, y=1..3)
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    boolean edge = x == 0 || x == size - 1 || z == 0 || z == size - 1;
                    // Laat een deuropening vrij in het midden van de voorkant (z = 0, x = size / 2, y=1..2)
                    boolean isDoorPos = (z == 0 && x == size / 2 && y <= 2);
                    if (edge && !isDoorPos) {
                        plan.add(new BlockPlacement(x, y, z, wall));
                    }
                }
            }
        }

        // Deur-opening in de muur: midden van de voorkant (z = 0, x = size / 2)
        int doorX = size / 2;
        int doorZ = 0;
        for (int y = 1; y <= 2; y++) {
            plan.add(new BlockPlacement(doorX, y, doorZ, Blocks.AIR.defaultBlockState()));
        }

        // Interieur leegmaken: alles binnen de muren tot aan het dak moet lucht zijn
        int roofY = 4;
        for (int y = 1; y < roofY; y++) {
            for (int x = 1; x < size - 1; x++) {
                for (int z = 1; z < size - 1; z++) {
                    plan.add(new BlockPlacement(x, y, z, Blocks.AIR.defaultBlockState()));
                }
            }
        }

        // Dak (vlak, op y=4)
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                plan.add(new BlockPlacement(x, roofY, z, roof));
            }
        }
    }


    /**
     * Zoek een geschikte bouworigin rondom het dorpscenter.
     * We lopen een paar afstanden in de vier hoofd-richtingen af en kiezen
     * de eerste positie waarvan de 5x5-voetafdruk niet overlapt met het opslag-huis.
     * De exacte blokken worden later tijdens het bouwen vrijgemaakt (bomen/gras etc).
     */
    
    /**
     * Kies een goede bouworigin rondom het dorp:
     * - niet overlappen met het opslag-huis
     * - bij voorkeur vlak terrein (kleine hoogte-span)
     * - niet bovenop bestaande gebouwen (veel solide niet-natuurlijke blokken)
     */
    private BlockPos findBuildOrigin(VillageData village) {
        BlockPos center = village.getCenter();

        // Storage footprint (5x5) zodat we daar niet overheen bouwen.
        boolean hasStorage = village.hasStoragePos();
        int sx1 = 0, sz1 = 0, sx2 = -1, sz2 = -1;
        if (hasStorage) {
            BlockPos storagePos = village.getStoragePos();
            // In VillageStructures: chest = origin.offset(1, 1, 2), origin = center.offset(3, 0, 0)
            // => origin = chestPos.offset(-1, -1, -2)
            BlockPos storageOrigin = storagePos.offset(-1, -1, -2);
            sx1 = storageOrigin.getX();
            sz1 = storageOrigin.getZ();
            sx2 = sx1 + 4;
            sz2 = sz1 + 4;
        }

        // Deterministische plekkeuze: elke keer dat een gebouw af is, schuift nextBuildIndex door.
        // We bouwen in ringen rond het center: +Z, +X, -Z, -X en dan verder weg.
        int baseIndex = Math.max(0, village.getNextBuildIndex());
        int spacing = 8;

        for (int attempt = 0; attempt < 16; attempt++) {
            int i = baseIndex + attempt;
            int ring = (i / 4) + 1;
            int side = i % 4;

            int ox = switch (side) {
                case 0 -> 0;
                case 1 -> spacing * ring;
                case 2 -> 0;
                default -> -spacing * ring;
            };
            int oz = switch (side) {
                case 0 -> spacing * ring;
                case 1 -> 0;
                case 2 -> -spacing * ring;
                default -> 0;
            };

            int baseX = center.getX() + ox;
            int baseZ = center.getZ() + oz;

            // 5x5 footprint van de builder-hut
            int bx1 = baseX;
            int bz1 = baseZ;
            int bx2 = bx1 + 4;
            int bz2 = bz1 + 4;

            // Niet overlappen met storage-huis
            if (hasStorage) {
                boolean overlap = !(bx2 < sx1 || bx1 > sx2 || bz2 < sz1 || bz1 > sz2);
                if (overlap) continue;
            }

            // Zet origin op grondhoogte van de footprint-hoek.
            int y = bot.level().getHeight(Heightmap.Types.WORLD_SURFACE, bx1, bz1) - 1;
            return new BlockPos(bx1, y, bz1);
        }

        // Fallback
        int defaultX = center.getX() + 6;
        int defaultZ = center.getZ();
        int defaultY = bot.level().getHeight(Heightmap.Types.WORLD_SURFACE, defaultX, defaultZ) - 1;
        return new BlockPos(defaultX, defaultY, defaultZ);
    }

private static class BlockPlacement {
        final int dx;
        final int dy;
        final int dz;
        final BlockState state;

        BlockPlacement(int dx, int dy, int dz, BlockState state) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.state = state;
        }
    }


    /**
     * Kleine helper om te checken of een blockstate planks is.
     * We gebruiken de standaard Minecraft-tag voor planks.
     */
    private boolean isPlanks(BlockState state) {
        return state != null && state.is(BlockTags.PLANKS);
    }



    /**
     * Checkt of er momenteel überhaupt items in de dorpsopslag (chest) liggen.
     * We gebruiken dit om te bepalen of de builder opnieuw mag starten nadat hij
     * eerder in wachtstand (waitingForResources) is gegaan.
     */
    private boolean hasAnyStorageItems(VillageData village) {
        if (village == null || !village.hasStoragePos()) {
            return false;
        }
        if (!(bot.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        BlockPos storagePos = village.getStoragePos();
        BlockEntity be = serverLevel.getBlockEntity(storagePos);
        if (!(be instanceof Container container)) {
            return false;
        }

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                return true;
            }
        }

        return false;
    }

}
