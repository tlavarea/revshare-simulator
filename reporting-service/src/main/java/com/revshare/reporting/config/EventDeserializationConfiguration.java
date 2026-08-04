package com.revshare.reporting.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.TransactionId;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The read side's half of the event payload contract.
 *
 * <p>This is the mirror of {@code EventSerializationConfiguration} in {@code commission-service}, and the two have to
 * be read together: the write side flattens {@code AgentId} and {@code TransactionId} to bare strings and {@code Money}
 * to a JSON number, so nothing here can reconstruct them by reflection. Jackson handles records natively, but it cannot
 * guess that the string {@code "9ae8-..."} was once a wrapper type.
 *
 * <p>Kept as a dedicated {@code eventObjectMapper} for the same reason the write side does: the payload is a published
 * contract, durable in a table and on a topic for as long as the log is retained. Sharing the application mapper would
 * let a property set for an HTTP response change how historical events parse.
 *
 * <h2>Tolerance</h2>
 *
 * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is disabled, deliberately, and it is the one place this side is deliberately
 * lax. The write side can add a field to an event and deploy before the read side knows about it; a strict reader would
 * turn that into a consumer crash loop on a topic it cannot skip past. Unknown fields are ignored, missing ones still
 * fail — which is the asymmetry a tolerant reader wants, since a field that vanished is a genuine contract break and a
 * field that appeared is not.
 *
 * <p>What is <em>not</em> relaxed: the domain records' own validating constructors still run. A
 * {@code CommissionCalculated} whose split does not balance is rejected here exactly as it would have been in the core.
 * Deserialising into the domain types rather than local DTOs is what buys that, and it is the main reason this module
 * depends on {@code domain-core} at all.
 */
@Configuration
public class EventDeserializationConfiguration {

    @Bean
    public ObjectMapper eventObjectMapper() {
        SimpleModule domainTypes = new SimpleModule("revshare-domain-types");
        domainTypes.addDeserializer(AgentId.class, new FromStringDeserializer<>(AgentId::fromString));
        domainTypes.addDeserializer(TransactionId.class, new FromStringDeserializer<>(TransactionId::fromString));
        domainTypes.addDeserializer(Money.class, new MoneyDeserializer());

        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(domainTypes)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Rebuilds an identifier wrapper from its bare string form. */
    private static final class FromStringDeserializer<T> extends JsonDeserializer<T> {
        private final Function<String, T> parse;

        private FromStringDeserializer(Function<String, T> parse) {
            this.parse = parse;
        }

        @Override
        public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return parse.apply(parser.getValueAsString());
        }
    }

    /**
     * Rebuilds money from a JSON number.
     *
     * <p>Read via {@link JsonParser#getDecimalValue()} rather than {@code getDoubleValue()}. The payload holds an exact
     * decimal, and routing it through a binary double would reintroduce, at the very last step, precisely the
     * representation error the domain avoids everywhere else.
     */
    private static final class MoneyDeserializer extends JsonDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            BigDecimal amount = parser.getDecimalValue();
            return Money.of(amount);
        }
    }
}
