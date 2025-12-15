package com.example.aicitybuilder.village;

import com.example.aicitybuilder.BotJobType;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Een simpele "Millénaire-achtige" job-ticket.
 *
 * Tickets worden door de VillageJobPlanner gepost en door bots geclaimd.
 *
 * Stap 5 uitbreiding:
 * - Optioneel kan een ticket een "requested item" bevatten (bv. "minecraft:oak_planks", 32).
 *   Hierdoor kan de planner missing-materials tickets posten zonder nieuwe ticket types te hoeven verzinnen.
 */
public class JobTicket {
    private final UUID id;
    private final BotJobType type;
    private final int priority;
    private final BlockPos target;
    private final long createdAtGameTime;

    // Optionele payload voor "haal/breng materiaal" tickets
    @Nullable private final String requestedItemId;
    private final int requestedCount;

    private JobStatus status = JobStatus.OPEN;
    private UUID claimedBy;

    /**
     * Backwards compatible constructor (zoals je huidige code).
     */
    public JobTicket(UUID id, BotJobType type, int priority, BlockPos target, long createdAtGameTime) {
        this(id, type, priority, target, createdAtGameTime, null, 0);
    }

    /**
     * Nieuwe constructor met optionele requested item payload.
     */
    public JobTicket(
            UUID id,
            BotJobType type,
            int priority,
            BlockPos target,
            long createdAtGameTime,
            @Nullable String requestedItemId,
            int requestedCount
    ) {
        this.id = id;
        this.type = type;
        this.priority = priority;
        this.target = target;
        this.createdAtGameTime = createdAtGameTime;
        this.requestedItemId = requestedItemId;
        this.requestedCount = Math.max(0, requestedCount);
    }

    /**
     * Factory voor "missing materials" tickets.
     * Je kunt dit straks gebruiken in VillageJobPlanner.
     */
    public static JobTicket material(BotJobType type, int priority, BlockPos target, long nowGameTime,
                                     String itemId, int count) {
        return new JobTicket(UUID.randomUUID(), type, priority, target, nowGameTime, itemId, count);
    }

    public UUID getId() {
        return id;
    }

    public BotJobType getType() {
        return type;
    }

    public int getPriority() {
        return priority;
    }

    public BlockPos getTarget() {
        return target;
    }

    public long getCreatedAtGameTime() {
        return createdAtGameTime;
    }

    public JobStatus getStatus() {
        return status;
    }

    public UUID getClaimedBy() {
        return claimedBy;
    }

    public boolean isClaimable() {
        return status == JobStatus.OPEN;
    }

    public void claim(UUID botId) {
        this.claimedBy = botId;
        this.status = JobStatus.CLAIMED;
    }

    public void complete() {
        this.status = JobStatus.COMPLETED;
    }

    public void fail() {
        this.status = JobStatus.FAILED;
    }

    // ---- Missing-material payload API ----

    public boolean hasRequestedItem() {
        return requestedItemId != null && !requestedItemId.isEmpty() && requestedCount > 0;
    }

    @Nullable
    public String getRequestedItemId() {
        return requestedItemId;
    }

    public int getRequestedCount() {
        return requestedCount;
    }
}
