package com.revshare.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.revshare.domain.shared.Money;
import com.revshare.reporting.AbstractMongoIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Guards the separation between the HTTP mapper and the event mapper.
 *
 * <p>This module has two {@link ObjectMapper} beans, and the reason is a trap that only armed itself when the web layer
 * arrived. Boot's own mapper is declared {@code @Primary} <em>and</em> {@code @ConditionalOnMissingBean}; because
 * {@code eventObjectMapper} already exists, Boot backs off, and the outcome is not "two mappers, each used correctly"
 * but <strong>no primary mapper at all</strong> — leaving the event mapper as the single candidate and therefore the
 * one Spring MVC serialises every response with.
 *
 * <p>Nothing would visibly break the day that happened. It is the next change that hurts: tuning the mapper for the API
 * would silently alter the payload format of every event thereafter, with the old and new shapes interleaved in one
 * outbox table and nothing recording which is which. That is precisely the failure
 * {@code EventSerializationConfiguration} on the write side was written to prevent, and it would have been reintroduced
 * here by adding a starter.
 *
 * <p>Which makes this a test worth having even though nothing today reads differently through the two mappers. It
 * asserts the wiring, not an output. Both assertions were watched failing with {@code WebSerializationConfiguration}'s
 * bean commented out: the two injections come back as the same instance, and the web mapper parses the event wire
 * format perfectly happily.
 */
@DisplayName("the web and event serialisers are separate")
class SerializationBoundaryIT extends AbstractMongoIT {

    /** Unqualified: whatever Spring MVC would inject into its message converters. */
    @Autowired
    private ObjectMapper webMapper;

    @Autowired
    @Qualifier("eventObjectMapper")
    private ObjectMapper eventMapper;

    @Test
    void thePrimaryMapperIsNotTheEventMapper() {
        assertThat(webMapper).isNotSameAs(eventMapper);
    }

    @Test
    void onlyTheEventMapperUnderstandsTheWireFormat() throws Exception {
        // Money crosses the wire as a bare JSON number, which needs the custom deserialiser the
        // event mapper carries and the web mapper deliberately does not.
        assertThat(eventMapper.readValue("1500.02", Money.class)).isEqualTo(Money.of("1500.02"));

        // If this ever stops throwing, the two beans have merged and the event contract has
        // quietly become whatever the API layer is configured for.
        assertThatThrownBy(() -> webMapper.readValue("1500.02", Money.class)).isInstanceOf(Exception.class);
    }
}
