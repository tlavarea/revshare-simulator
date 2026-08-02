package com.revshare.domain.port.out;

import com.revshare.domain.event.DomainEvent;
import java.util.List;

/**
 * Driven port for announcing domain events to the rest of the system.
 *
 * <p>Names no broker. The core emits {@link DomainEvent}s and an adapter decides that they become Kafka records on
 * particular topics, with a particular serialization, keyed by {@link DomainEvent#partitionKey()}.
 *
 * <p><strong>Implementation note.</strong> Publishing must be atomic with the database write that produced the event.
 * Writing to Postgres and then to Kafka in the same method is a dual write: a crash between the two leaves the read
 * side permanently behind, with nothing to detect it. The intended adapter is the transactional outbox, appending
 * events to an outbox table inside the same transaction as the aggregate update and relaying them afterwards. That is
 * also what makes {@link #publish} safe to call inside a {@code @Transactional} method, which is the only place it
 * should be called.
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);

    /**
     * Publishes several events as one unit.
     *
     * <p>Pricing a single closing can emit a commission event, a cap-threshold event, and a revenue share event
     * together. They describe one indivisible business moment and must not be separable by a failure between calls.
     */
    void publishAll(List<? extends DomainEvent> events);
}
