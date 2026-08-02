package com.revshare.domain.event;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.TransactionId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An agent reached their commission cap.
 *
 * <p>Emitted once per cap year, on the closing that crosses the threshold. A distinct event rather than a flag on
 * {@link CommissionCalculated} because the moment matters to subscribers that have nothing to do with commission: it is
 * when the agent's fee schedule changes, when their upline stops earning revenue share from them for the year, and when
 * a congratulatory notification is worth sending. Each of those is a different consumer, and none of them should have
 * to inspect every commission event to spot the transition.
 */
public record CapThresholdReached(
        UUID eventId,
        Instant occurredAt,
        AgentId agentId,
        CapYear capYear,
        TransactionId reachedOnTransaction,
        LocalDate reachedOn,
        Money capAmount)
        implements DomainEvent {

    public CapThresholdReached {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(capYear, "capYear must not be null");
        Objects.requireNonNull(reachedOnTransaction, "reachedOnTransaction must not be null");
        Objects.requireNonNull(reachedOn, "reachedOn must not be null");
        Objects.requireNonNull(capAmount, "capAmount must not be null");

        if (!capYear.contains(reachedOn)) {
            throw new IllegalArgumentException("cap reached on " + reachedOn + " which is outside cap year " + capYear);
        }
    }

    @Override
    public String partitionKey() {
        return agentId.toString();
    }
}
