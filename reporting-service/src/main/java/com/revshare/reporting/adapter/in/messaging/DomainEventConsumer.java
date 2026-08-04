package com.revshare.reporting.adapter.in.messaging;

import com.revshare.domain.event.DomainEvent;
import com.revshare.reporting.service.DashboardProjector;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The read side's way in. Consumes the domain event stream and hands each event to the projector.
 *
 * <h2>One listener over four topics</h2>
 *
 * <p>Rather than a listener per topic, because ordering is guaranteed per partition and never across topics, so
 * separate listeners would buy nothing but three copies of this method. The events that must stay ordered relative to
 * one another — an agent's commission events and the cap announcement they trigger — are already on one topic and one
 * partition by the write side's design.
 *
 * <h2>Delivery and failure</h2>
 *
 * <p>Offsets commit after the listener returns, so delivery is at-least-once and a crash mid-batch replays. That is
 * safe precisely because {@link DashboardProjector#apply} is idempotent on event id; the two halves are designed
 * together and neither works alone.
 *
 * <p>A projection failure is allowed to propagate. Spring Kafka retries with backoff and, when the retries are
 * exhausted, stops the container rather than seeking past the record. Blocking one partition is the correct failure
 * here and matches the relay's stance on the other side of the topic: skipping a commission event would leave a
 * dashboard permanently wrong with nothing to indicate it, whereas a stalled consumer is visible in lag metrics within
 * a minute.
 */
@Component
public class DomainEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);

    private final DomainEventReader reader;
    private final DashboardProjector projector;

    public DomainEventConsumer(DomainEventReader reader, DashboardProjector projector) {
        this.reader = reader;
        this.projector = projector;
    }

    @KafkaListener(
            topics = {EventTopics.COMMISSION, EventTopics.REVENUE_SHARE, EventTopics.TRANSACTION, EventTopics.AGENT},
            groupId = "${revshare.reporting.consumer-group:reporting-service}")
    public void onEvent(ConsumerRecord<String, String> record) {
        String eventType = header(record, DomainEventReader.EVENT_TYPE_HEADER);

        if (eventType == null) {
            // Every event the relay publishes carries this header. One without it did not come
            // from the outbox, so there is nothing sensible to do with it and nothing that
            // retrying would fix.
            log.warn(
                    "record on {}-{} at offset {} has no {} header; dropping",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    DomainEventReader.EVENT_TYPE_HEADER);
            return;
        }

        Optional<DomainEvent> parsed = reader.read(eventType, record.value());
        if (parsed.isEmpty()) {
            log.warn("unknown event type '{}' on {}; ignoring", eventType, record.topic());
            return;
        }

        DomainEvent event = parsed.get();
        boolean applied = projector.apply(event);

        if (applied) {
            log.debug("projected {} {}", eventType, event.eventId());
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var found = record.headers().lastHeader(name);
        return found == null ? null : new String(found.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
