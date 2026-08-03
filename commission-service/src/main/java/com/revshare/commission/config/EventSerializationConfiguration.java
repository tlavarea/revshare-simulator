package com.revshare.commission.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.TransactionId;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The JSON format for outbox payloads.
 *
 * <p>A dedicated mapper rather than the application-wide auto-configured one, and that is a deliberate boundary. The
 * payload written to the outbox is a <em>published contract</em>: the reporting service parses it, possibly weeks
 * later, from events already durable in the table. Sharing the web layer's mapper would mean a property change made for
 * an HTTP response — a naming strategy, a date format, an inclusion rule — silently altering the format of every event
 * thereafter, with the old and new shapes interleaved in one table and nothing recording which is which.
 *
 * <p>The value-object serializers exist for the same reason. Left to reflection, Jackson renders the domain's wrapper
 * types structurally, so an agent id becomes {@code {"agentId":{"value":"9ae8..."}}} and a money amount
 * {@code {"awarded":{"amount":500.00}}}. That is valid but awkward to consume and leaks the core's internal shape into
 * the wire format. Flattening them to a string and a number keeps the payload readable and, more importantly, keeps it
 * stable if {@code Money} ever gains a currency field.
 */
@Configuration
public class EventSerializationConfiguration {

    @Bean
    public ObjectMapper eventObjectMapper() {
        SimpleModule domainTypes = new SimpleModule("revshare-domain-types");
        domainTypes.addSerializer(AgentId.class, new ToStringSerializer<>(AgentId::toString));
        domainTypes.addSerializer(TransactionId.class, new ToStringSerializer<>(TransactionId::toString));
        domainTypes.addSerializer(Money.class, new MoneySerializer());

        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(domainTypes)
                // ISO-8601 strings. An epoch number in an event payload is unreadable in a
                // debugging session and ambiguous about precision.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /** Renders an identifier wrapper as its bare string form. */
    private static final class ToStringSerializer<T> extends JsonSerializer<T> {
        private final java.util.function.Function<T, String> render;

        private ToStringSerializer(java.util.function.Function<T, String> render) {
            this.render = render;
        }

        @Override
        public void serialize(T value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeString(render.apply(value));
        }
    }

    /**
     * Renders money as a JSON number at its natural scale.
     *
     * <p>Written as a number rather than a string so a consumer can aggregate without parsing, and from the
     * {@link java.math.BigDecimal} rather than a double so the exact cent value survives the round trip.
     */
    private static final class MoneySerializer extends JsonSerializer<Money> {
        @Override
        public void serialize(Money value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeNumber(value.amount());
        }
    }
}
