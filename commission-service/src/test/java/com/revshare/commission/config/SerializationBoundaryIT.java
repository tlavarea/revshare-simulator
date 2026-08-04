package com.revshare.commission.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.revshare.commission.AbstractPostgresIT;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Guards the separation between the HTTP mapper and the event mapper.
 *
 * <p>This module has two {@link ObjectMapper} beans, and the reason is a trap that only armed itself when the web layer
 * arrived — the same one the read side hit, and the one {@code EventSerializationConfiguration} predicted in a comment
 * long before it could happen. Boot's own mapper is declared {@code @Primary} <em>and</em>
 * {@code @ConditionalOnMissingBean}; because {@code eventObjectMapper} already exists, Boot backs off, and the outcome
 * is not "two mappers, each used correctly" but <strong>no primary mapper at all</strong>, leaving the event mapper as
 * the single candidate and therefore the one Spring MVC serialises every response with.
 *
 * <p>Nothing would visibly break the day that happened. The event mapper renders {@code Money} as a bare number and
 * {@code AgentId} as a string, which is what this API wants anyway, so the responses would look right. It is the next
 * change that hurts: tuning that mapper for the API would silently alter the payload format of every event thereafter,
 * with old and new shapes interleaved in one outbox table and nothing recording which is which.
 *
 * <p>Which makes this a test of wiring rather than of output, and one worth having precisely because nothing reads
 * differently through the two mappers today. Both assertions were watched failing with
 * {@code WebSerializationConfiguration}'s bean commented out: the two injections come back as the same instance, and
 * the domain wrappers render flattened through what should be the plain web mapper.
 */
@DisplayName("the web and event serialisers are separate")
class SerializationBoundaryIT extends AbstractPostgresIT {

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
    void onlyTheEventMapperFlattensTheDomainWrappers() throws Exception {
        AgentId agent = AgentId.of(UUID.fromString("6f1a5b2c-0000-4000-8000-000000000003"));

        // The published event contract: an id is a bare string, money a bare number.
        assertThat(eventMapper.writeValueAsString(agent)).isEqualTo("\"6f1a5b2c-0000-4000-8000-000000000003\"");
        assertThat(eventMapper.writeValueAsString(Money.of("1500.02"))).isEqualTo("1500.02");

        // If these ever start matching the above, the two beans have merged and the event
        // contract has quietly become whatever the API layer is configured for. The web layer
        // does not rely on this rendering - ClosingReceiptView hands Jackson strings and
        // BigDecimals already, which is why the merge would go unnoticed without this test.
        assertThat(webMapper.writeValueAsString(agent)).contains("value");
        assertThat(webMapper.writeValueAsString(Money.of("1500.02"))).contains("amount");
    }
}
