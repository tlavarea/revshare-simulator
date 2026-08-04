package com.revshare.commission.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The HTTP layer's {@link ObjectMapper}, kept separate from the event one.
 *
 * <p>{@link EventSerializationConfiguration} predicted this in as many words — "a property change made for an HTTP
 * response silently altering the format of every event" — and adding the web starter to this module is the moment that
 * stops being hypothetical. The read side hit the identical trap when it grew an API; the mechanism is worth stating
 * again here because nothing about it is visible at either call site.
 *
 * <p>Spring Boot's own {@code ObjectMapper} is declared {@code @Primary} <em>and</em>
 * {@code @ConditionalOnMissingBean}. This module already declares {@code eventObjectMapper}, which is an
 * {@code ObjectMapper}, so Boot backs off entirely. The result is not "two mappers, each used where it belongs", it is
 * <strong>no primary mapper at all</strong> — leaving {@code eventObjectMapper} as the only candidate and therefore the
 * one Spring MVC hands every HTTP response to.
 *
 * <p>The damage would be quiet rather than loud. The event mapper renders {@code Money} as a bare number and
 * {@code AgentId} as a string, which is what the API wants anyway, so responses would look correct on day one. It is
 * the next change that hurts: any tuning of that mapper for the API — a naming strategy, an inclusion rule, a date
 * format — would land on the outbox payload contract, and events already durable in the table would stop matching
 * events written afterwards, with nothing recording which is which.
 *
 * <p>Declaring the web mapper explicitly and marking it {@code @Primary} restores the separation. The event mapper is
 * reachable only through {@code @Qualifier("eventObjectMapper")}, which is how its consumers already ask for it.
 * {@code SerializationBoundaryIT} is the guard, watched failing with this bean removed.
 */
@Configuration
public class WebSerializationConfiguration {

    /**
     * Built through {@link Jackson2ObjectMapperBuilder} rather than constructed directly, so it keeps everything Boot
     * would have configured — the well-known module set, the {@code spring.jackson.*} properties, and the customisers.
     * The point is to restore Boot's default, not to invent a second convention.
     */
    @Bean
    @Primary
    public ObjectMapper webObjectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.build();
    }
}
