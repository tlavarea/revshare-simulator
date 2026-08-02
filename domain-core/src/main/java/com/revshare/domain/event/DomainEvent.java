package com.revshare.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened in the domain, worth telling other bounded contexts about.
 *
 * <p>These types live in the core, not in a messaging adapter. What happened is a domain fact; Kafka is one way of
 * announcing it. Keeping the events here means the write side can be tested end to end without a broker, and means
 * swapping the transport does not touch a single business rule.
 *
 * <p>Sealed, so a consumer dispatching over the event stream gets an exhaustive {@code switch} and a compile error when
 * a new event type is added rather than a silent fall-through at runtime.
 *
 * <p>{@code occurredAt} is supplied by the caller rather than read from a clock here. The core has no clock by design:
 * replaying an event log must reproduce the original timestamps, not stamp everything with the moment of the replay.
 */
public sealed interface DomainEvent
        permits TransactionClosed, CommissionCalculated, CapThresholdReached, RevenueShareDistributed {

    /** Unique per emission. The consumer's idempotency key. */
    UUID eventId();

    /** When the fact became true, not when it was published. */
    Instant occurredAt();

    /**
     * Identity of the aggregate this event concerns, used as the partition key.
     *
     * <p>Partitioning by aggregate is what gives per-agent ordering: all of one agent's commission events land on one
     * partition and are consumed in the order they were produced, which matters because cap progress is cumulative and
     * a reordered pair of closings would project the wrong cap state.
     */
    String partitionKey();
}
