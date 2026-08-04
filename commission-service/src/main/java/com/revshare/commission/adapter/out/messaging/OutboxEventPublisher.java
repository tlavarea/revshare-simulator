package com.revshare.commission.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.revshare.commission.adapter.out.persistence.entity.OutboxEntity;
import com.revshare.commission.adapter.out.persistence.jpa.OutboxJpaRepository;
import com.revshare.domain.event.AgentEnrolled;
import com.revshare.domain.event.AgentTerminated;
import com.revshare.domain.event.CapThresholdReached;
import com.revshare.domain.event.CommissionCalculated;
import com.revshare.domain.event.DomainEvent;
import com.revshare.domain.event.RevenueShareDistributed;
import com.revshare.domain.event.TransactionClosed;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes domain events to the transactional outbox.
 *
 * <p>The whole reason this class exists instead of a Kafka template call. Publishing directly would be a dual write:
 * the database commit and the broker send are two independent outcomes, and a crash between them leaves the read side
 * permanently behind with nothing in either system able to detect the gap. Appending to a table in the caller's
 * transaction makes the event durable exactly when the state change is durable.
 *
 * <p>{@link Propagation#MANDATORY} enforces that. Calling this outside a transaction is a programming error — it would
 * create an event with no guarantee the state change it describes was ever committed — so it fails loudly rather than
 * appearing to work.
 *
 * <p>Nothing here knows about Kafka. A separate relay polls the table and publishes; this adapter's only job is
 * durability and ordering metadata.
 */
@Component
public class OutboxEventPublisher implements com.revshare.domain.port.out.DomainEventPublisher {

    private final OutboxJpaRepository outbox;
    private final ObjectMapper objectMapper;

    /**
     * Takes the dedicated event mapper by name, not whatever {@code ObjectMapper} happens to be in the context. The
     * payload format is a contract with the reporting service, and it must not shift because a web layer reconfigured
     * its own serialisation. See {@code EventSerializationConfiguration}.
     */
    public OutboxEventPublisher(OutboxJpaRepository outbox, @Qualifier("eventObjectMapper") ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        outbox.save(toEntity(event));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishAll(List<? extends DomainEvent> events) {
        outbox.saveAll(events.stream().map(this::toEntity).toList());
    }

    private OutboxEntity toEntity(DomainEvent event) {
        // aggregateId and partitionKey coincide today, because every event partitions on
        // the aggregate it concerns. They are stored separately because they answer
        // different questions - "what is this about" versus "what must stay ordered with
        // it" - and a future event may well want to key on something other than its own
        // aggregate.
        return new OutboxEntity(
                event.eventId(),
                aggregateTypeOf(event),
                event.partitionKey(),
                event.getClass().getSimpleName(),
                event.partitionKey(),
                serialise(event),
                event.occurredAt());
    }

    /**
     * Which aggregate the event concerns, used by the relay to route to a topic.
     *
     * <p>An exhaustive switch over the sealed {@link DomainEvent} hierarchy, so adding an event type is a compile error
     * here rather than a silent fall-through to a default that routes it somewhere wrong.
     */
    private static String aggregateTypeOf(DomainEvent event) {
        return switch (event) {
            case TransactionClosed ignored -> "transaction";
            case CommissionCalculated ignored -> "commission";
            case CapThresholdReached ignored -> "commission";
            case RevenueShareDistributed ignored -> "revenue-share";
            case AgentEnrolled ignored -> "agent";
            case AgentTerminated ignored -> "agent";
        };
    }

    private String serialise(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Unrecoverable and not retryable: the event cannot be represented at all.
            // Failing here rolls back the whole transaction, which is correct — better no
            // state change than a state change nobody downstream will ever hear about.
            throw new IllegalStateException(
                    "could not serialise " + event.getClass().getSimpleName() + " " + event.eventId(), e);
        }
    }
}
