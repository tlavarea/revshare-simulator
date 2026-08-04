package com.revshare.reporting.adapter.out.mongo.document;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A marker that one event has already been folded into the read model.
 *
 * <p>The read side's idempotency key. Delivery from the outbox relay is at-least-once by design, and most of what the
 * projector does is additive — a redelivered {@code CommissionCalculated} would count the same closing twice, and a
 * redelivered {@code RevenueShareDistributed} would pay an upline twice on a dashboard. Recording the event id and
 * refusing the second application is what turns at-least-once delivery into effectively-once projection.
 *
 * <p>The id is the event's own {@code eventId}, so uniqueness is enforced by the primary key rather than by a separate
 * index.
 *
 * <h2>Why this is worth a collection rather than a field on the dashboard</h2>
 *
 * <p>One {@code RevenueShareDistributed} touches up to five dashboards. Tracking applied events per dashboard would
 * mean five independent records of one fact, and an unbounded array growing inside a document that is read on every
 * page load. A separate collection keeps the dashboards small and makes the check a primary key lookup.
 *
 * <h2>Expiry</h2>
 *
 * <p>The TTL is the honest bound on this collection's growth, and the number is not arbitrary: an event that Kafka can
 * no longer redeliver can no longer be a duplicate, so the marker only has to outlive the topic's retention. Thirty
 * days is comfortably beyond the seven this project's broker is configured for. Setting it <em>shorter</em> than
 * retention would be the subtle way to break this — replayed history would silently double-count with nothing to show
 * for it.
 */
@Document(collection = "processed_event")
public class ProcessedEventDocument {

    @Id
    private String eventId;

    private String eventType;
    private Instant occurredAt;

    /**
     * When the read side applied it. Also the TTL anchor.
     *
     * <p>Anchored on processing rather than on {@code occurredAt} so that replaying an old event from the start of the
     * log does not create a marker that Mongo expires moments later, which would leave the replay unprotected against
     * its own redelivery.
     */
    @Indexed(name = "ttl_processed_at", expireAfter = "30d")
    private Instant processedAt;

    protected ProcessedEventDocument() {
        // Spring Data materialisation.
    }

    public ProcessedEventDocument(String eventId, String eventType, Instant occurredAt, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.processedAt = processedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
