package com.example.aicitybuilder.village;

import com.example.aicitybuilder.BotJobType;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Simpele server-side jobboard.
 *
 * - VillageJobPlanner post tickets
 * - Bots claimen tickets op basis van type + priority
 */
public class JobBoard {

    private final Map<UUID, JobTicket> ticketsById = new HashMap<>();
    private final Map<UUID, UUID> claimedByBot = new HashMap<>(); // botUuid -> ticketUuid

    public Collection<JobTicket> getAllTickets() {
        return ticketsById.values();
    }

    public Optional<JobTicket> getTicket(UUID id) {
        return Optional.ofNullable(ticketsById.get(id));
    }

    public Optional<JobTicket> getClaimedTicket(UUID botId) {
        UUID ticketId = claimedByBot.get(botId);
        if (ticketId == null) return Optional.empty();
        return getTicket(ticketId);
    }

    public void clearClaim(UUID botId) {
        UUID ticketId = claimedByBot.remove(botId);
        if (ticketId != null) {
            JobTicket t = ticketsById.get(ticketId);
            // Laat status staan (CLAIMED) om dubbele claims te voorkomen.
            // Fail/complete gebeurt expliciet.
        }
    }

    public void post(JobTicket ticket) {
        ticketsById.put(ticket.getId(), ticket);
    }

    public boolean hasOpenTicketNear(BotJobType type, BlockPos target, int rangeBlocks) {
        int rangeSq = rangeBlocks * rangeBlocks;
        for (JobTicket t : ticketsById.values()) {
            if (t.getStatus() != JobStatus.OPEN) continue;
            if (t.getType() != type) continue;
            if (t.getTarget().distSqr(target) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    public Optional<JobTicket> claimBest(UUID botId, BotJobType preferredType, BlockPos botPos) {
        // 1 bot = 1 ticket tegelijk
        if (claimedByBot.containsKey(botId)) {
            return getClaimedTicket(botId);
        }

        JobTicket best = null;
        double bestScore = -Double.MAX_VALUE;

        for (JobTicket t : ticketsById.values()) {
            if (!t.isClaimable()) continue;
            if (preferredType != null && t.getType() != preferredType) continue;

            // Score: priority zwaar, afstand licht.
            double dist = botPos.distSqr(t.getTarget());
            double score = (t.getPriority() * 1000.0) - dist;

            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }

        if (best == null) return Optional.empty();

        best.claim(botId);
        claimedByBot.put(botId, best.getId());
        return Optional.of(best);
    }

    public void complete(UUID ticketId) {
        JobTicket t = ticketsById.get(ticketId);
        if (t == null) return;
        t.complete();
        if (t.getClaimedBy() != null) {
            claimedByBot.remove(t.getClaimedBy());
        }
    }

    public void fail(UUID ticketId) {
        JobTicket t = ticketsById.get(ticketId);
        if (t == null) return;
        t.fail();
        if (t.getClaimedBy() != null) {
            claimedByBot.remove(t.getClaimedBy());
        }
    }

    /**
     * Ruim oude completed/failed tickets op.
     */
    public void cleanup(long nowGameTime, long ttlTicks) {
        Iterator<Map.Entry<UUID, JobTicket>> it = ticketsById.entrySet().iterator();
        while (it.hasNext()) {
            JobTicket t = it.next().getValue();
            if (t.getStatus() == JobStatus.OPEN || t.getStatus() == JobStatus.CLAIMED) continue;
            if (nowGameTime - t.getCreatedAtGameTime() > ttlTicks) {
                it.remove();
            }
        }
    }
}
