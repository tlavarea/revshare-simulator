package com.revshare.reporting.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.revshare.domain.event.AgentEnrolled;
import com.revshare.domain.event.AgentTerminated;
import com.revshare.domain.event.CapThresholdReached;
import com.revshare.domain.event.CommissionCalculated;
import com.revshare.domain.event.DomainEvent;
import com.revshare.domain.event.RevenueShareDistributed;
import com.revshare.domain.event.TransactionClosed;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Turns a record off the topic back into a domain event.
 *
 * <p>The type is taken from the {@code event-type} header rather than from a discriminator inside the JSON. That is
 * what the relay puts there, and it is the better place for it: a consumer can route, dead-letter or count events
 * without parsing a payload it may not understand, and the payload stays a plain rendering of the record rather than
 * carrying Jackson's polymorphic type machinery in the contract.
 *
 * <p>An unrecognised type is <strong>not</strong> an error. The write side may add an event and deploy before this
 * service knows about it, and a hard failure there would be a crash loop on a partition that cannot be skipped past. An
 * event this service has never heard of cannot, by construction, affect any projection it maintains — so it is logged
 * and dropped. Contrast a <em>known</em> type that fails to parse, which is a genuine contract break and does throw.
 */
@Component
public class DomainEventReader {

    /** Header written by the outbox relay. Its value is the event record's simple class name. */
    static final String EVENT_TYPE_HEADER = "event-type";

    private static final Map<String, Class<? extends DomainEvent>> TYPES = Map.of(
            TransactionClosed.class.getSimpleName(), TransactionClosed.class,
            CommissionCalculated.class.getSimpleName(), CommissionCalculated.class,
            CapThresholdReached.class.getSimpleName(), CapThresholdReached.class,
            RevenueShareDistributed.class.getSimpleName(), RevenueShareDistributed.class,
            AgentEnrolled.class.getSimpleName(), AgentEnrolled.class,
            AgentTerminated.class.getSimpleName(), AgentTerminated.class);

    private final ObjectMapper objectMapper;

    /**
     * Takes the dedicated event mapper by name, not whatever {@code ObjectMapper} is in the context. The payload format
     * is a contract with the write side; see {@code EventDeserializationConfiguration}.
     */
    public DomainEventReader(@Qualifier("eventObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param eventType the {@code event-type} header
     * @param payload the record value
     * @return the parsed event, or empty if this service does not know the type
     * @throws IllegalStateException if a known type cannot be parsed
     */
    public Optional<DomainEvent> read(String eventType, String payload) {
        Class<? extends DomainEvent> type = TYPES.get(eventType);
        if (type == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, type));
        } catch (Exception e) {
            // Deliberately fatal. A known event type that will not parse means the payload
            // contract changed underneath this service, and projecting the rest of the stream
            // around the gap would produce a dashboard that is quietly wrong rather than
            // visibly stuck.
            throw new IllegalStateException("could not parse " + eventType + " payload", e);
        }
    }
}
