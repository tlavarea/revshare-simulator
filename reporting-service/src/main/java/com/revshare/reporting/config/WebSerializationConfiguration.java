package com.revshare.reporting.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The HTTP layer's {@link ObjectMapper}, kept separate from the event one.
 *
 * <p>This bean exists to defuse a trap that only armed itself when this module grew a web layer, and the write side's
 * {@code EventSerializationConfiguration} predicted it in as many words: "a property change made for an HTTP response
 * silently altering the format of every event".
 *
 * <p>The mechanism is worth stating precisely, because nothing about it is visible at the call site. Spring Boot's own
 * {@code ObjectMapper} is declared {@code @Primary} <em>and</em> {@code @ConditionalOnMissingBean}. This module already
 * declares {@code eventObjectMapper}, which is an {@code ObjectMapper}. So Boot backs off entirely — and the result is
 * not "two mappers, web picks the right one", it is <strong>no primary mapper at all</strong>, leaving
 * {@code eventObjectMapper} as the only candidate and therefore the one Spring MVC hands every HTTP response to.
 *
 * <p>The damage would be quiet rather than loud. The event mapper disables {@code FAIL_ON_UNKNOWN_PROPERTIES} and
 * carries deserialisers that rebuild {@code AgentId} and {@code Money} from the wire format, so responses would render
 * plausibly today. It is the next change that hurts: any tuning of this mapper for the API — a naming strategy, an
 * inclusion rule, a date format — would land on the event payload contract, and events already durable in the outbox
 * would stop matching events written after the change, with nothing recording which is which.
 *
 * <p>Declaring the web mapper explicitly and marking it {@code @Primary} restores the separation. The event mapper is
 * now reachable only through {@code @Qualifier("eventObjectMapper")}, which is how both of its consumers already ask
 * for it. {@code WebAndEventMappersAreSeparateTest} is the guard.
 */
@Configuration
public class WebSerializationConfiguration {

    /**
     * Built through {@link Jackson2ObjectMapperBuilder} rather than constructed directly, so it keeps everything Boot
     * would have configured — the well-known module set, the {@code spring.jackson.*} properties, and the customisers.
     * The point here is to restore Boot's default, not to invent a second convention.
     */
    @Bean
    @Primary
    public ObjectMapper webObjectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.build();
    }
}
