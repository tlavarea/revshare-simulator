package com.revshare.reporting.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revshare.domain.event.CapThresholdReached;
import com.revshare.domain.event.CommissionCalculated;
import com.revshare.domain.event.DomainEvent;
import com.revshare.domain.event.RevenueShareDistributed;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.reporting.config.EventDeserializationConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payload contract, pinned against literal JSON.
 *
 * <p>A unit test with no containers and no Spring context, and the fixtures are hand-written strings rather than
 * anything produced by a serialiser. That is the whole point. Round-tripping an event through a matched writer and
 * reader proves only that the two agree with each other; it passes just as happily after both have drifted. These
 * strings are what is actually on the topic today, so if the write side changes shape, the test that fails names the
 * field that moved.
 *
 * <p>They are also the readable specification of the format. Anyone wondering how {@code Money} or {@code AgentId}
 * crosses the wire can read it here in four lines instead of inferring it from two Jackson modules in different
 * modules.
 */
@DisplayName("reading events off the wire")
class DomainEventReaderTest {

    private final DomainEventReader reader =
            new DomainEventReader(new EventDeserializationConfiguration().eventObjectMapper());

    private static final String COMMISSION_CALCULATED = """
            {
              "eventId": "6f1a5b2c-0000-4000-8000-000000000001",
              "occurredAt": "2025-04-01T17:30:00Z",
              "split": {
                "transactionId": "6f1a5b2c-0000-4000-8000-000000000002",
                "agentId": "6f1a5b2c-0000-4000-8000-000000000003",
                "closedOn": "2025-04-01",
                "grossCommissionIncome": 10000.00,
                "agentEarnings": 8500.00,
                "companyEarnings": 1500.00,
                "capContribution": 1500.00,
                "postCapFeeCharged": 0.00,
                "revenueShareEligibleGross": 10000.00,
                "pricedUnderPostCapFee": false,
                "reachedCapOnThisTransaction": false
              },
              "progressAfter": {
                "agentId": "6f1a5b2c-0000-4000-8000-000000000003",
                "capYear": {
                  "start": "2025-03-14",
                  "endExclusive": "2026-03-14",
                  "ordinal": 0
                },
                "contributed": 1500.00,
                "capAmount": 12000.00
              }
            }
            """;

    @Test
    void identifiersArriveAsBareStringsAndMoneyAsNumbers() {
        DomainEvent event =
                reader.read("CommissionCalculated", COMMISSION_CALCULATED).orElseThrow();

        assertThat(event).isInstanceOf(CommissionCalculated.class);
        CommissionCalculated commission = (CommissionCalculated) event;

        assertThat(commission.split().agentId().toString()).isEqualTo("6f1a5b2c-0000-4000-8000-000000000003");
        assertThat(commission.split().grossCommissionIncome().amount()).isEqualByComparingTo("10000.00");
        assertThat(commission.progressAfter().capYear().ordinal()).isZero();
        assertThat(commission.progressAfter().capYear().start().toString()).isEqualTo("2025-03-14");
    }

    @Test
    void moneyKeepsItsExactCentsThroughTheRoundTrip() {
        String payload = COMMISSION_CALCULATED.replace("1500.00", "1500.02").replace("8500.00", "8499.98");

        CommissionCalculated commission = (CommissionCalculated)
                reader.read("CommissionCalculated", payload).orElseThrow();

        // Routed through a double this would read back as 1500.0200000000000954969436135889.
        assertThat(commission.split().capContribution().amount()).isEqualByComparingTo("1500.02");
    }

    @Test
    void unknownFieldsAreToleratedSoTheWriteSideCanDeployFirst() {
        String payload = COMMISSION_CALCULATED.replace(
                "\"closedOn\": \"2025-04-01\",", "\"closedOn\": \"2025-04-01\", \"settlementBatch\": \"B-77\",");

        assertThat(reader.read("CommissionCalculated", payload)).isPresent();
    }

    @Test
    void aMissingFieldIsAContractBreakAndFails() {
        String payload = COMMISSION_CALCULATED.replace("\"capContribution\": 1500.00,", "");

        // The asymmetry with the previous test is deliberate: a field that appeared is a
        // forward-compatible addition, a field that vanished is the write side breaking
        // something this service depends on.
        assertThatThrownBy(() -> reader.read("CommissionCalculated", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not parse CommissionCalculated");
    }

    @Test
    void domainInvariantsStillRunOnDeserialisation() {
        // A split whose agent and company shares no longer add up to the gross. Deserialising
        // into the domain record rather than a local DTO is what makes this fail here instead
        // of becoming a wrong number on a dashboard.
        String payload = COMMISSION_CALCULATED.replace("\"agentEarnings\": 8500.00", "\"agentEarnings\": 9000.00");

        assertThatThrownBy(() -> reader.read("CommissionCalculated", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capThresholdReachedParses() {
        String payload = """
                {
                  "eventId": "6f1a5b2c-0000-4000-8000-000000000004",
                  "occurredAt": "2025-09-22T14:00:00Z",
                  "agentId": "6f1a5b2c-0000-4000-8000-000000000003",
                  "capYear": {
                    "start": "2025-03-14",
                    "endExclusive": "2026-03-14",
                    "ordinal": 0
                  },
                  "reachedOnTransaction": "6f1a5b2c-0000-4000-8000-000000000005",
                  "reachedOn": "2025-09-22",
                  "capAmount": 12000.00
                }
                """;

        CapThresholdReached event = (CapThresholdReached)
                reader.read("CapThresholdReached", payload).orElseThrow();

        assertThat(event.reachedOn().toString()).isEqualTo("2025-09-22");
        assertThat(event.capAmount().amount()).isEqualByComparingTo("12000.00");
    }

    @Test
    void revenueShareDistributedParsesItsAwards() {
        String payload = """
                {
                  "eventId": "6f1a5b2c-0000-4000-8000-000000000006",
                  "occurredAt": "2025-04-01T17:30:00Z",
                  "distribution": {
                    "transactionId": "6f1a5b2c-0000-4000-8000-000000000002",
                    "contributor": "6f1a5b2c-0000-4000-8000-000000000003",
                    "closedOn": "2025-04-01",
                    "eligibleGross": 10000.00,
                    "awards": [
                      {
                        "beneficiary": "6f1a5b2c-0000-4000-8000-000000000007",
                        "contributor": "6f1a5b2c-0000-4000-8000-000000000003",
                        "transactionId": "6f1a5b2c-0000-4000-8000-000000000002",
                        "tier": "TIER_1",
                        "eligibleGross": 10000.00,
                        "entitlement": 500.00,
                        "awarded": 500.00,
                        "forfeited": 0.00,
                        "forfeitReason": "NONE"
                      }
                    ]
                  }
                }
                """;

        RevenueShareDistributed event = (RevenueShareDistributed)
                reader.read("RevenueShareDistributed", payload).orElseThrow();

        assertThat(event.distribution().awards()).hasSize(1);
        // The enum crosses the wire by name, so renaming a tier constant is a breaking change.
        assertThat(event.distribution().awards().getFirst().tier()).isEqualTo(RevenueShareTier.TIER_1);
        assertThat(event.distribution().totalAwarded().amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void anUnknownTypeIsEmptyRatherThanAnError() {
        Optional<DomainEvent> parsed = reader.read("AgentOnboarded", "{\"eventId\":\"x\"}");

        // Empty, not an exception. An event type this service has never heard of cannot affect
        // any projection it maintains, and throwing would stall a partition it can never
        // get past.
        assertThat(parsed).isEmpty();
    }
}
