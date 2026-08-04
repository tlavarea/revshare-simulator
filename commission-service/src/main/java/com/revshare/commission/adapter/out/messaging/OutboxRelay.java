package com.revshare.commission.adapter.out.messaging;

import com.revshare.commission.adapter.out.persistence.entity.OutboxEntity;
import com.revshare.commission.adapter.out.persistence.jpa.OutboxJpaRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes outbox rows to Kafka and marks them sent.
 *
 * <p>The second half of the transactional outbox. {@code OutboxEventPublisher} makes an event durable in the same
 * transaction as the state change that caused it; this moves it to the broker afterwards. Splitting the two is what
 * removes the dual write — there is no moment where the database has committed and the event is lost, only a moment
 * where it is committed and not yet delivered.
 *
 * <h2>Delivery semantics</h2>
 *
 * <p>At-least-once, deliberately. The relay publishes, then marks the row published; a crash in between means the event
 * is sent again on the next poll. Exactly-once would need Kafka transactions spanning the broker and the database,
 * which is a great deal of machinery to avoid a duplicate that consumers must already tolerate. Every consumer-facing
 * write in this system is idempotent for exactly that reason.
 *
 * <h2>Ordering</h2>
 *
 * <p>Two things preserve it, and both matter. Rows are claimed in {@code (occurred_at, sequence_number)} order and sent
 * one at a time, waiting for each acknowledgement before the next — so a batch cannot be reordered in flight. The
 * sequence number matters because {@code occurred_at} alone ties: every event one closing emits shares a single
 * {@code Instant}, so a commission event and the cap-threshold event it triggered sort equally on timestamp, and an
 * {@code ORDER BY} tie has no guaranteed result. The sequence number is a Postgres identity value assigned at insert
 * time, so it discriminates same-closing events even when nothing else on the row does. And the batch <strong>stops at
 * the first failure</strong> rather than skipping past it: if event three fails, events four and five are left for the
 * next poll, so a later event for an agent can never overtake an earlier one that has not landed yet.
 *
 * <p>The cost is throughput — a synchronous send per record. That is the right trade here. Reordering an agent's
 * commission events would corrupt a cumulative cap projection, and the volume is bounded by closings per second, which
 * for a brokerage is not a demanding number.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxJpaRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    @Value("${revshare.outbox.batch-size:100}")
    private int batchSize;

    public OutboxRelay(OutboxJpaRepository outbox, KafkaTemplate<String, String> kafka, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
    }

    /**
     * Claims and publishes one batch.
     *
     * <p>Transactional because the claim uses {@code FOR UPDATE SKIP LOCKED} — the row locks have to be held for as
     * long as the publishing takes, so a second relay cannot pick up the same rows. It also means a failure part-way
     * rolls back the {@code published_at} stamps for that batch, leaving those events to be re-sent rather than
     * silently dropped. The claimed rows stay managed for the life of that transaction, which is what makes
     * {@link OutboxEntity#markPublished} persist without an explicit save.
     *
     * <p><strong>Must be called from another bean.</strong> {@code @Transactional} is proxy-applied, so an in-class
     * caller would bypass it and get none of the above — no held locks, and detached rows whose {@code published_at} is
     * never flushed. {@link OutboxRelayScheduler} exists to be that caller; see its Javadoc.
     *
     * @return how many events were published
     */
    @Transactional
    public int relayBatch() {
        List<OutboxEntity> claimed = outbox.claimUnpublished(batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEntity event : claimed) {
            try {
                send(event);
            } catch (Exception e) {
                // Stop the batch here rather than continuing. Skipping a failed event would let
                // a later event for the same agent be published before an earlier one, which is
                // exactly the reordering the whole design is trying to prevent.
                log.warn(
                        "stopping relay batch at event {} ({}); {} published, remainder deferred",
                        event.getId(),
                        event.getEventType(),
                        published,
                        e);
                break;
            }
            event.markPublished(clock.instant());
            published++;
        }

        return published;
    }

    private void send(OutboxEntity event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                EventTopics.forAggregateType(event.getAggregateType()),
                null,
                event.getPartitionKey(),
                event.getPayload());

        // Headers let a consumer route and deduplicate without parsing the payload, which
        // matters for a dead-letter or audit consumer that has no reason to understand the body.
        record.headers()
                .add(new RecordHeader("event-id", event.getId().toString().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("event-type", event.getEventType().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(
                        "occurred-at", event.getOccurredAt().toString().getBytes(StandardCharsets.UTF_8)));

        // Blocking on the acknowledgement is what keeps the batch ordered. Fire-and-forget
        // would let record N+1 land before record N after a retry.
        kafka.send(record).get();
    }
}
