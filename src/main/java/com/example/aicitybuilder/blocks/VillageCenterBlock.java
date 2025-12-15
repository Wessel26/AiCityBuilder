package com.example.aicitybuilder.blocks;

import com.example.aicitybuilder.VillageData;
import com.example.aicitybuilder.settlement.SettlementManager;
import com.example.aicitybuilder.VillageStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class VillageCenterBlock extends Block {

    public VillageCenterBlock(Properties props) {
        super(props);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return super.getStateForPlacement(ctx);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (level.isClientSide) return;
        if (!(level instanceof ServerLevel)) return;

        ServerLevel serverLevel = (ServerLevel) level;
        VillageData data = VillageData.get(serverLevel);

        // Claim het center als er nog geen center bestaat.
        if (!data.hasCenter()) {
            data.setCenter(pos);
        }

        // New multi-settlement system: placing a center always ensures a settlement exists.
        SettlementManager.runtime(serverLevel).getOrCreateAt(pos);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel)) return InteractionResult.SUCCESS;

        ServerLevel serverLevel = (ServerLevel) level;
        VillageData data = VillageData.get(serverLevel);

        // Als er al een ander center is, toon info en doe niets.
        if (data.hasCenter() && !data.getCenter().equals(pos)) {
            player.sendSystemMessage(Component.literal(
                    "Dit is niet het Village Center. Huidig center: " + data.getCenter().toShortString()
            ));
            return InteractionResult.CONSUME;
        }

        // Als er geen center was, claim dit blok als center.
        if (!data.hasCenter()) {
            data.setCenter(pos);
            SettlementManager.runtime(serverLevel).getOrCreateAt(pos);
            player.sendSystemMessage(Component.literal("Village Center gezet op: " + pos.toShortString()));
            return InteractionResult.CONSUME;
        }

        // Shift + rechtsklik: ensure storage house
        if (player.isShiftKeyDown()) {
            VillageStructures.ensureStorageHouse(serverLevel, data);
            BlockPos storage = data.getStoragePos();
            player.sendSystemMessage(Component.literal(
                    "Storage ensured. Storage: " + (storage != null ? storage.toShortString() : "none")
            ));
            return InteractionResult.CONSUME;
        }

        // Normale right-click: info
        BlockPos center = data.getCenter();
        BlockPos storage = data.getStoragePos();

        player.sendSystemMessage(Component.literal("=== Village Info ==="));
        player.sendSystemMessage(Component.literal("Center: " + (center != null ? center.toShortString() : "none")));
        player.sendSystemMessage(Component.literal("Storage: " + (storage != null ? storage.toShortString() : "none")));
        player.sendSystemMessage(Component.literal("(Shift + Right-click om storage te bouwen/ensure'n)"));

        return InteractionResult.CONSUME;
    }
}
