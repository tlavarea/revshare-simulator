package com.revshare.commission.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revshare.commission.AbstractPostgresIT;
import com.revshare.commission.TestBrokerage;
import com.revshare.commission.adapter.out.persistence.jpa.OutboxJpaRepository;
import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.port.out.AgentRepository;
import com.revshare.domain.port.out.CapProgressRepository;
import com.revshare.domain.shared.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * JSON in, priced closing out, through the real stack.
 *
 * <p>The join {@code TransactionControllerTest} cannot make. That test stubs the port to stage a failure a database
 * will not produce on request, and so proves nothing about whether the endpoint really prices anything; this drives the
 * whole write path — Hibernate, Liquibase's schema, the cap row, the ledger and the outbox — against a real Postgres
 * and reads the answer back as JSON.
 *
 * <p>The outbox assertions are the ones worth having here specifically. Everything else could in principle be checked a
 * layer down, but "an HTTP request produces events in the same transaction as the state change it describes" is a
 * property of the composition, and this is the only place the composition exists.
 */
@AutoConfigureMockMvc
@DisplayName("the closing API over a real write path")
class TransactionApiIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AgentRepository agents;

    @Autowired
    private CapProgressRepository capProgress;

    @Autowired
    private OutboxJpaRepository outbox;

    private TestBrokerage brokerage;

    @BeforeEach
    void setUp() {
        brokerage = new TestBrokerage(agents);
    }

    private static String closing(UUID transactionId, AgentId agent, String gross) {
        return closing(transactionId, agent, gross, "2024-04-15");
    }

    private static String closing(UUID transactionId, AgentId agent, String gross, String closedOn) {
        return """
                {
                  "transactionId": "%s",
                  "agentId": "%s",
                  "closedOn": "%s",
                  "salePrice": 1000000.00,
                  "grossCommissionIncome": %s,
                  "side": "LISTING",
                  "propertyReference": "PROP-%s"
                }
                """.formatted(transactionId, agent, closedOn, gross, transactionId);
    }

    private org.springframework.test.web.servlet.ResultActions record(String body) throws Exception {
        return mvc.perform(
                post("/transactions").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Nested
    @DisplayName("pricing")
    class Pricing {

        @Test
        @DisplayName("splits 85/15 and returns the reasoning, not just the payout")
        void pricesAndExplains() throws Exception {
            Agent agent = brokerage.founder();

            record(closing(UUID.randomUUID(), agent.id(), "10000.00"))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.agentId").value(agent.id().toString()))
                    .andExpect(jsonPath("$.alreadyRecorded").value(false))
                    .andExpect(jsonPath("$.split.agentEarnings").value(8500.00))
                    .andExpect(jsonPath("$.split.companyEarnings").value(1500.00))
                    .andExpect(jsonPath("$.split.capContribution").value(1500.00))
                    .andExpect(jsonPath("$.split.pricedUnderPostCapFee").value(false))
                    .andExpect(jsonPath("$.split.reachedCap").value(false))
                    .andExpect(jsonPath("$.capProgress.contributed").value(1500.00))
                    .andExpect(jsonPath("$.capProgress.remaining").value(10500.00))
                    .andExpect(jsonPath("$.capProgress.capped").value(false));
        }

        @Test
        @DisplayName("reports the anniversary window the cap accrues over")
        void carriesTheCapYear() throws Exception {
            Agent agent = brokerage.founder();

            // Everyone in TestBrokerage joins on 2024-01-15, so the window runs from the
            // anniversary and not from January 1st. A client showing "you have $10,500 left"
            // needs to say left until when.
            record(closing(UUID.randomUUID(), agent.id(), "10000.00"))
                    .andExpect(jsonPath("$.capProgress.capYear.start").value("2024-01-15"))
                    .andExpect(jsonPath("$.capProgress.capYear.endExclusive").value("2025-01-15"))
                    .andExpect(jsonPath("$.capProgress.capYear.ordinal").value(0));
        }

        @Test
        @DisplayName("prices the straddling closing under the split and pays the excess to the agent")
        void reportsACapCrossing() throws Exception {
            Agent agent = brokerage.founder();
            // $75,000 of gross leaves $750 of cap remaining, at 15%.
            record(closing(UUID.randomUUID(), agent.id(), "75000.00")).andExpect(status().isCreated());

            record(closing(UUID.randomUUID(), agent.id(), "20000.00"))
                    .andExpect(jsonPath("$.split.capContribution").value(750.00))
                    .andExpect(jsonPath("$.split.agentEarnings").value(19250.00))
                    .andExpect(jsonPath("$.split.reachedCap").value(true))
                    .andExpect(jsonPath("$.split.pricedUnderPostCapFee").value(false))
                    .andExpect(jsonPath("$.capProgress.capped").value(true))
                    .andExpect(jsonPath("$.capProgress.remaining").value(0))
                    // Only the pre-cap slice funds revenue share, and the response says which
                    // slice that was rather than leaving it to be re-derived client-side.
                    .andExpect(jsonPath("$.split.revenueShareEligibleGross").value(5000.00));
        }

        @Test
        @DisplayName("charges the flat fee on the closing after the cap")
        void reportsAPostCapFee() throws Exception {
            Agent agent = brokerage.founder();
            record(closing(UUID.randomUUID(), agent.id(), "80000.00")).andExpect(status().isCreated());

            record(closing(UUID.randomUUID(), agent.id(), "9000.00"))
                    .andExpect(jsonPath("$.split.postCapFeeCharged").value(285.00))
                    .andExpect(jsonPath("$.split.capContribution").value(0))
                    .andExpect(jsonPath("$.split.pricedUnderPostCapFee").value(true))
                    .andExpect(jsonPath("$.revenueShare.awards").isEmpty());
        }

        @Test
        @DisplayName("serialises money as a number at its natural scale")
        void moneyKeepsItsCents() throws Exception {
            Agent agent = brokerage.founder();

            // Not a string, and not truncated to 8500.0 - a client summing these must not have
            // to parse, and a trailing cent must not disappear on the way out.
            record(closing(UUID.randomUUID(), agent.id(), "10000.00"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"agentEarnings\":8500.00")));
        }
    }

    @Nested
    @DisplayName("revenue share")
    class RevenueShare {

        @Test
        @DisplayName("returns every award, including the ones that earned nothing and why")
        void explainsForfeitures() throws Exception {
            List<Agent> chain = brokerage.chain(3);
            Agent contributor = chain.get(2);

            // Nobody upline has produced anything, so nobody is eligible. A response listing
            // only payments could not distinguish that from having no upline at all.
            record(closing(UUID.randomUUID(), contributor.id(), "10000.00"))
                    .andExpect(jsonPath("$.revenueShare.awards.length()").value(2))
                    .andExpect(jsonPath("$.revenueShare.totalAwarded").value(0))
                    .andExpect(jsonPath("$.revenueShare.awards[0].tier").value("TIER_1"))
                    .andExpect(jsonPath("$.revenueShare.awards[0].depth").value(1))
                    .andExpect(jsonPath("$.revenueShare.awards[0].rate").value("5%"))
                    .andExpect(jsonPath("$.revenueShare.awards[0].entitlement").value(500.00))
                    .andExpect(
                            jsonPath("$.revenueShare.awards[0].forfeitReason").value("BENEFICIARY_NOT_PRODUCING"));
        }

        @Test
        @DisplayName("pays a producing sponsor at the tier 1 rate")
        void paysAProducingSponsor() throws Exception {
            List<Agent> chain = brokerage.chain(2);
            Agent sponsor = chain.get(0);
            Agent contributor = chain.get(1);

            // The sponsor clears the $450 trailing production threshold first.
            record(closing(UUID.randomUUID(), sponsor.id(), "5000.00", "2024-02-15"))
                    .andExpect(status().isCreated());

            record(closing(UUID.randomUUID(), contributor.id(), "10000.00", "2024-03-15"))
                    .andExpect(jsonPath("$.revenueShare.totalAwarded").value(500.00))
                    .andExpect(jsonPath("$.revenueShare.totalForfeited").value(0))
                    .andExpect(jsonPath("$.revenueShare.awards[0].beneficiary")
                            .value(sponsor.id().toString()))
                    .andExpect(jsonPath("$.revenueShare.awards[0].awarded").value(500.00))
                    .andExpect(
                            jsonPath("$.revenueShare.awards[0].forfeitReason").value("NONE"));
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("answers 200 to a redelivered closing and charges the cap once")
        void aReplayIsNotACreation() throws Exception {
            Agent agent = brokerage.founder();
            UUID transactionId = UUID.randomUUID();

            record(closing(transactionId, agent.id(), "20000.00")).andExpect(status().isCreated());

            record(closing(transactionId, agent.id(), "20000.00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alreadyRecorded").value(true))
                    // The original pricing, read back - not a second one applied on top.
                    .andExpect(jsonPath("$.split.agentEarnings").value(17000.00))
                    .andExpect(jsonPath("$.capProgress.contributed").value(3000.00));

            var progress = capProgress
                    .find(agent.id(), agent.capYearOn(java.time.LocalDate.of(2024, 4, 15)))
                    .orElseThrow();
            assertThat(progress.contributed()).isEqualTo(Money.of("3000.00"));
        }

        @Test
        @DisplayName("omits revenue share on a replay rather than reporting zeros")
        void aReplayDoesNotClaimNobodyWasPaid() throws Exception {
            List<Agent> chain = brokerage.chain(2);
            Agent contributor = chain.get(1);
            UUID transactionId = UUID.randomUUID();

            record(closing(transactionId, contributor.id(), "10000.00"))
                    .andExpect(jsonPath("$.revenueShare.awards.length()").value(1));

            // The replay path does not re-read the ledger, by design - it writes nothing and
            // announces nothing. Rendering its empty placeholder as zeros would assert that
            // this closing paid nobody, which is a different claim from "this response does
            // not know".
            record(closing(transactionId, contributor.id(), "10000.00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revenueShare").doesNotExist());
        }

        @Test
        @DisplayName("a redelivered closing announces nothing a second time")
        void aReplayEmitsNoEvents() throws Exception {
            Agent agent = brokerage.founder();
            UUID transactionId = UUID.randomUUID();

            record(closing(transactionId, agent.id(), "20000.00")).andExpect(status().isCreated());
            long afterFirst = outbox.count();

            record(closing(transactionId, agent.id(), "20000.00")).andExpect(status().isOk());

            assertThat(outbox.count())
                    .as("a retried request must not re-announce the closing downstream")
                    .isEqualTo(afterFirst);
        }
    }

    @Nested
    @DisplayName("the outbox")
    class Outbox {

        @Test
        @DisplayName("writes the events in the same transaction the request committed")
        void anHttpRequestFillsTheOutbox() throws Exception {
            Agent agent = brokerage.founder();
            long before = outbox.count();

            record(closing(UUID.randomUUID(), agent.id(), "80000.00")).andExpect(status().isCreated());

            assertThat(outbox.count()).isGreaterThan(before);
            assertThat(outbox.findAll())
                    .extracting(e -> e.getEventType())
                    .contains("CommissionCalculated", "CapThresholdReached");
            assertThat(outbox.countByPublishedAtIsNull())
                    .as("the relay, not the request, is what publishes them")
                    .isPositive();
        }
    }

    @Nested
    @DisplayName("rejections")
    class Rejections {

        @Test
        @DisplayName("an agent this service never enrolled is 404, not 400")
        void unknownAgentIs404() throws Exception {
            // The request is well-formed and the id is a valid UUID. What is missing is the
            // agent, which is a fact about this service rather than about the caller's JSON.
            record(closing(UUID.randomUUID(), AgentId.newId(), "10000.00"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Unknown agent"));
        }

        @Test
        @DisplayName("a closing the domain refuses is 400, with the core's own reason")
        void aDomainInvariantIs400() throws Exception {
            Agent agent = brokerage.founder();

            // Gross commission above the sale price. The rule lives in ClosedTransaction and is
            // deliberately not restated as a validation annotation here; the adapter's job is to
            // translate the refusal, not to duplicate the rule.
            String impossible = closing(UUID.randomUUID(), agent.id(), "2000000.00");

            record(impossible)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid closing"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("exceeds sale price")));
        }

        @Test
        @DisplayName("nothing is written when the closing is rejected")
        void aRejectionLeavesNoTrace() throws Exception {
            Agent agent = brokerage.founder();
            long before = outbox.count();

            record(closing(UUID.randomUUID(), agent.id(), "2000000.00")).andExpect(status().isBadRequest());

            assertThat(outbox.count()).isEqualTo(before);
            assertThat(capProgress.find(agent.id(), agent.capYearOn(java.time.LocalDate.of(2024, 4, 15))))
                    .isEmpty();
        }
    }
}
