package com.revshare.domain.transaction;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a closed transaction.
 *
 * <p>Client-assigned, and carried unchanged through every event the transaction produces. That makes it the natural
 * idempotency key for the read side: a {@code CommissionCalculated} event redelivered by Kafka is recognized and
 * discarded by transaction id, which is what lets at-least-once delivery behave as effectively-once without a
 * distributed transaction.
 */
public record TransactionId(UUID value) implements Comparable<TransactionId> {

    public TransactionId {
        Objects.requireNonNull(value, "transaction id must not be null");
    }

    public static TransactionId of(UUID value) {
        return new TransactionId(value);
    }

    public static TransactionId fromString(String value) {
        return new TransactionId(UUID.fromString(value));
    }

    public static TransactionId newId() {
        return new TransactionId(UUID.randomUUID());
    }

    @Override
    public int compareTo(TransactionId other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
