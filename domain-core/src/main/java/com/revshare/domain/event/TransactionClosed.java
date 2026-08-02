package com.revshare.domain.event;

import com.revshare.domain.transaction.ClosedTransaction;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A closing settled and produced gross commission. The event that starts every chain in this system.
 *
 * <p>Carries the whole {@link ClosedTransaction} rather than a reference to it, so a consumer never has to call back
 * into the write side to find out what happened. That is what makes the two services independently deployable: the
 * reporting side can be down for an hour, come back, and reconstruct everything from the log alone.
 */
public record TransactionClosed(UUID eventId, Instant occurredAt, ClosedTransaction transaction)
        implements DomainEvent {

    public TransactionClosed {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(transaction, "transaction must not be null");
    }

    public static TransactionClosed of(UUID eventId, Instant occurredAt, ClosedTransaction transaction) {
        return new TransactionClosed(eventId, occurredAt, transaction);
    }

    /** Keyed by agent, not by transaction: cap progress is per agent and must stay ordered. */
    @Override
    public String partitionKey() {
        return transaction.agentId().toString();
    }
}
