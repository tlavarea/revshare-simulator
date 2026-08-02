package com.revshare.domain.event;

import com.revshare.domain.revshare.RevenueShareDistribution;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Revenue share arising from one closing was allocated across the contributor's upline.
 *
 * <p>Partitioned by the <em>contributor</em>, not by any beneficiary, because one event concerns up to five
 * beneficiaries at once and no single one of them can own the ordering. The invariant that actually needs ordering is
 * per-contributor: annual tier maxima are drawn down per contributing agent, so those draws must be applied in the
 * order they happened.
 *
 * <p>The consequence for the read side is that a beneficiary's earnings arrive spread across partitions and must be
 * aggregated, rather than read as a single ordered stream. That is exactly the shape a denormalized document read model
 * handles well and a normalized relational one does not.
 */
public record RevenueShareDistributed(UUID eventId, Instant occurredAt, RevenueShareDistribution distribution)
        implements DomainEvent {

    public RevenueShareDistributed {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(distribution, "distribution must not be null");
    }

    @Override
    public String partitionKey() {
        return distribution.contributor().toString();
    }
}
