package com.revshare.commission.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Persistence mapping for a pending outbound event.
 *
 * <p>Written inside the same transaction as the aggregate change that produced it, which is the entire point: the event
 * becomes durable exactly when the state change does, so there is no window in which one exists without the other.
 *
 * <p>{@code payload} is mapped to {@code jsonb} rather than {@code text}. The relay only ever passes it through, but
 * storing it as a real JSON type means Postgres validates it on write and an operator can query into a stuck event
 * without parsing it by hand.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity implements Persistable<UUID> {

    /**
     * True until persisted or loaded. With a client-assigned id, Spring Data would otherwise treat every new row as a
     * potential update and issue a SELECT before each INSERT — and a single closing writes up to three events.
     */
    @Transient
    private boolean isNew = true;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    /** Decided by the domain event, not by the relay. See {@code DomainEvent#partitionKey}. */
    @Column(name = "partition_key", nullable = false, updatable = false)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * A Postgres identity sequence value, assigned at insert time — never {@code occurredAt}. Every event one closing
     * emits shares a single {@code Instant}, and {@code created_at} is frozen for the whole transaction, so neither
     * timestamp can tell two same-closing events apart. This can. {@code insertable = false} because it is
     * database-generated, the same reason {@code createdAt} is.
     */
    @Column(name = "sequence_number", nullable = false, insertable = false, updatable = false)
    private long sequenceNumber;

    /** Null until the relay has published it. Rows are marked, never deleted. */
    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEntity() {
        // Required by Hibernate.
    }

    public OutboxEntity(
            UUID id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String partitionKey,
            String payload,
            Instant occurredAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
    }
}
