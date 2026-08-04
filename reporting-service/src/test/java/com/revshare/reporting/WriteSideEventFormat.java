package com.revshare.reporting;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.event.DomainEvent;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.TransactionId;
import java.io.IOException;

/**
 * Renders an event the way {@code commission-service} renders it.
 *
 * <p>A deliberate stand-in for the write side, used only to put realistic bytes on a test topic. It mirrors
 * {@code EventSerializationConfiguration}: identifiers flattened to strings, money as a JSON number, dates ISO-8601.
 *
 * <p>Duplicating those few lines here is the lesser of two evils. The alternative is a test-scoped dependency from
 * {@code reporting-service} on {@code commission-service}, which would put an arrow between two services that are
 * supposed to share nothing but a topic and the event types — and it would make this module's tests fail to compile
 * whenever the write side did, for no gain in what is actually being verified.
 *
 * <p>The risk that this drifts from the real writer is real, and it is why {@code DomainEventReaderTest} pins the wire
 * format against literal JSON rather than against a round trip through this class. A round trip only proves the two
 * halves here agree with each other.
 */
public final class WriteSideEventFormat {

    private static final ObjectMapper MAPPER = build();

    private WriteSideEventFormat() {}

    public static String serialise(DomainEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise " + event, e);
        }
    }

    private static ObjectMapper build() {
        SimpleModule domainTypes = new SimpleModule("revshare-domain-types");
        domainTypes.addSerializer(AgentId.class, new ToStringSerializer<>());
        domainTypes.addSerializer(TransactionId.class, new ToStringSerializer<>());
        domainTypes.addSerializer(Money.class, new MoneySerializer());

        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(domainTypes)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    private static final class ToStringSerializer<T> extends JsonSerializer<T> {
        @Override
        public void serialize(T value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeString(value.toString());
        }
    }

    private static final class MoneySerializer extends JsonSerializer<Money> {
        @Override
        public void serialize(Money value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeNumber(value.amount());
        }
    }
}
