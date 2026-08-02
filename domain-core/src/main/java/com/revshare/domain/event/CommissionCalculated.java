package com.revshare.domain.event;

import com.revshare.domain.agent.CapYear;
import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionSplit;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A closing was priced under a commission plan.
 *
 * <p>Carries the resulting cap progress alongside the split. The reporting side needs both to render a dashboard, and
 * shipping them together means the read model is projected from a single event rather than stitched from two that could
 * arrive out of order.
 */
public record CommissionCalculated(UUID eventId, Instant occurredAt, CommissionSplit split, CapProgress progressAfter)
        implements DomainEvent {

    public CommissionCalculated {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(split, "split must not be null");
        Objects.requireNonNull(progressAfter, "progressAfter must not be null");

        if (!split.agentId().equals(progressAfter.agentId())) {
            throw new IllegalArgumentException("split and cap progress refer to different agents");
        }
    }

    public CapYear capYear() {
        return progressAfter.capYear();
    }

    @Override
    public String partitionKey() {
        return split.agentId().toString();
    }
}
