package com.example.aicitybuilder.village;

import com.example.aicitybuilder.BotJobType;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Een simpele "Millénaire-achtige" job-ticket.
 *
 * Tickets worden door de VillageJobPlanner gepost en door bots geclaimd.
 */
public class JobTicket {
    private final UUID id;
    private final BotJobType type;
    private final int priority;
    private final BlockPos target;
    private final long createdAtGameTime;

    private JobStatus status = JobStatus.OPEN;
    private UUID claimedBy;

    public JobTicket(UUID id, BotJobType type, int priority, BlockPos target, long createdAtGameTime) {
        this.id = id;
        this.type = type;
        this.priority = priority;
        this.target = target;
        this.createdAtGameTime = createdAtGameTime;
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
}
