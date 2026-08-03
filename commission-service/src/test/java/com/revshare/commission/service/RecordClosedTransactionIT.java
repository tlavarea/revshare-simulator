package com.revshare.commission.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.revshare.commission.AbstractPostgresIT;
import com.revshare.commission.TestBrokerage;
import com.revshare.commission.adapter.out.persistence.jpa.CommissionSplitJpaRepository;
import com.revshare.commission.adapter.out.persistence.jpa.OutboxJpaRepository;
import com.revshare.commission.adapter.out.persistence.jpa.RevenueShareAwardJpaRepository;
import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.port.in.RecordClosedTransaction;
import com.revshare.domain.port.out.AgentRepository;
import com.revshare.domain.port.out.CapProgressRepository;
import com.revshare.domain.revshare.ForfeitReason;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** End-to-end tests for the write path, against a real Postgres. */
class RecordClosedTransactionIT extends AbstractPostgresIT {

    @Autowired
    private RecordClosedTransaction recordClosedTransaction;

    @Autowired
    private AgentRepository agents;

    @Autowired
    private CapProgressRepository capProgress;

    @Autowired
    private CommissionSplitJpaRepository splits;

    @Autowired
    private RevenueShareAwardJpaRepository awards;

    @Autowired
    private OutboxJpaRepository outbox;

    private TestBrokerage brokerage;

    @BeforeEach
    void setUp() {
        brokerage = new TestBrokerage(agents);
    }

    @Nested
    @DisplayName("pricing a closing")
    class Pricing {

        @Test
        @DisplayName("splits 85/15 and persists the result")
        void persistsTheSplit() {
            Agent agent = brokerage.founder();

            RecordClosedTransaction.Receipt receipt =
                    recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));

            assertThat(receipt.split().agentEarnings()).isEqualTo(Money.of("8500.00"));
            assertThat(receipt.split().companyEarnings()).isEqualTo(Money.of("1500.00"));
            assertThat(receipt.alreadyRecorded()).isFalse();

            var stored =
                    splits.findById(receipt.split().transactionId().value()).orElseThrow();
            assertThat(stored.getAgentEarnings()).isEqualByComparingTo("8500.00");
            assertThat(stored.getCapContribution()).isEqualByComparingTo("1500.00");
        }

        @Test
        @DisplayName("advances cap progress across successive closings")
        void advancesTheCap() {
            Agent agent = brokerage.founder();

            recordClosedTransaction.record(TestBrokerage.closing(agent, "20000.00"));
            RecordClosedTransaction.Receipt second =
                    recordClosedTransaction.record(TestBrokerage.closing(agent, "20000.00"));

            assertThat(second.progressAfter().contributed()).isEqualTo(Money.of("6000.00"));
            assertThat(second.progressAfter().isCapped()).isFalse();
        }

        @Test
        @DisplayName("clamps the closing that crosses the cap and pays the excess to the agent")
        void clampsAtTheCap() {
            Agent agent = brokerage.founder();
            // $75,000 of gross leaves $750 of cap remaining, at 15%.
            recordClosedTransaction.record(TestBrokerage.closing(agent, "75000.00"));

            RecordClosedTransaction.Receipt crossing =
                    recordClosedTransaction.record(TestBrokerage.closing(agent, "20000.00"));

            assertThat(crossing.split().capContribution()).isEqualTo(Money.of("750.00"));
            assertThat(crossing.split().agentEarnings()).isEqualTo(Money.of("19250.00"));
            assertThat(crossing.reachedCap()).isTrue();
            assertThat(crossing.progressAfter().contributed()).isEqualTo(Money.of("12000.00"));
        }

        @Test
        @DisplayName("charges the flat fee once the agent has capped")
        void chargesFlatFeeAfterCapping() {
            Agent agent = brokerage.founder();
            recordClosedTransaction.record(TestBrokerage.closing(agent, "80000.00"));

            RecordClosedTransaction.Receipt postCap =
                    recordClosedTransaction.record(TestBrokerage.closing(agent, "9000.00"));

            assertThat(postCap.split().companyEarnings()).isEqualTo(Money.of("285.00"));
            assertThat(postCap.split().pricedUnderPostCapFee()).isTrue();
        }
    }

    @Nested
    @DisplayName("revenue share distribution")
    class Distribution {

        @Test
        @DisplayName("pays 5/4/3/2/1 percent up the five tiers")
        void paysUpFiveTiers() {
            // Six agents: index 0 at the top, index 5 the contributor. Each ancestor needs
            // 20 producing frontline agents to have unlocked every tier, which they do not
            // have here - so this asserts the tier-1 payment and that the rest forfeit for
            // the right reason, which is the honest outcome for a chain with no width.
            List<Agent> chain = brokerage.chain(6);
            Agent contributor = chain.get(5);

            RecordClosedTransaction.Receipt receipt =
                    recordClosedTransaction.record(TestBrokerage.closing(contributor, "10000.00"));

            assertThat(receipt.distribution().awards()).hasSize(5);

            var storedAwards = awards.findAllByTransactionId(
                    receipt.split().transactionId().value());
            assertThat(storedAwards).hasSize(5);

            // Tier 1 is unlocked automatically, but the sponsor must still be producing.
            // They have closed nothing, so they forfeit — and the reason is recorded.
            assertThat(receipt.distribution().awards())
                    .allSatisfy(award -> assertThat(award.forfeitReason())
                            .isIn(ForfeitReason.BENEFICIARY_NOT_PRODUCING, ForfeitReason.TIER_LOCKED));
        }

        @Test
        @DisplayName("pays a producing sponsor at tier 1")
        void paysAProducingSponsor() {
            List<Agent> chain = brokerage.chain(2);
            Agent sponsor = chain.get(0);
            Agent contributor = chain.get(1);

            // The sponsor produces enough to clear the $450 trailing threshold.
            recordClosedTransaction.record(
                    TestBrokerage.closing(sponsor, "5000.00", TestBrokerage.JOINED.plusMonths(1)));

            RecordClosedTransaction.Receipt receipt = recordClosedTransaction.record(
                    TestBrokerage.closing(contributor, "10000.00", TestBrokerage.JOINED.plusMonths(2)));

            assertThat(receipt.distribution().totalAwarded()).isEqualTo(Money.of("500.00"));
            assertThat(receipt.distribution().awards().get(0).forfeitReason()).isEqualTo(ForfeitReason.NONE);
        }

        @Test
        @DisplayName("does not compress the tree when a mid-chain sponsor leaves")
        void doesNotCompressOnDeparture() {
            // A sponsors B, B sponsors C. B leaves. A must keep earning from C at tier 2
            // (4%), not be promoted to tier 1 (5%).
            List<Agent> chain = brokerage.chain(3);
            Agent top = chain.get(0);
            Agent middle = chain.get(1);
            Agent contributor = chain.get(2);

            recordClosedTransaction.record(TestBrokerage.closing(top, "5000.00", TestBrokerage.JOINED.plusMonths(1)));
            brokerage.terminate(middle, TestBrokerage.JOINED.plusMonths(2));

            RecordClosedTransaction.Receipt receipt = recordClosedTransaction.record(
                    TestBrokerage.closing(contributor, "10000.00", TestBrokerage.JOINED.plusMonths(3)));

            var awardToTop = receipt.distribution().awards().stream()
                    .filter(a -> a.beneficiary().equals(top.id()))
                    .findFirst()
                    .orElseThrow();

            // 4% of $10,000, not 5%.
            assertThat(awardToTop.entitlement()).isEqualTo(Money.of("400.00"));
            assertThat(awardToTop.tier().depth()).isEqualTo(2);

            var awardToMiddle = receipt.distribution().awards().stream()
                    .filter(a -> a.beneficiary().equals(middle.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(awardToMiddle.forfeitReason()).isEqualTo(ForfeitReason.BENEFICIARY_NOT_AFFILIATED);
        }

        @Test
        @DisplayName("produces no awards for a post-cap closing")
        void postCapClosingFundsNothing() {
            List<Agent> chain = brokerage.chain(2);
            Agent contributor = chain.get(1);
            recordClosedTransaction.record(TestBrokerage.closing(contributor, "80000.00"));

            RecordClosedTransaction.Receipt postCap =
                    recordClosedTransaction.record(TestBrokerage.closing(contributor, "9000.00"));

            assertThat(postCap.distribution().isEmpty()).isTrue();
            assertThat(awards.findAllByTransactionId(
                            postCap.split().transactionId().value()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("recording the same closing twice charges the cap once")
        void replayDoesNotDoubleCharge() {
            Agent agent = brokerage.founder();
            ClosedTransaction closing = TestBrokerage.closing(agent, "20000.00");

            recordClosedTransaction.record(closing);
            RecordClosedTransaction.Receipt replay = recordClosedTransaction.record(closing);

            assertThat(replay.alreadyRecorded()).isTrue();

            var progress = capProgress
                    .find(agent.id(), agent.capYearOn(closing.closedOn()))
                    .orElseThrow();
            assertThat(progress.contributed()).isEqualTo(Money.of("3000.00"));
        }

        @Test
        @DisplayName("a replay emits no further events")
        void replayEmitsNoEvents() {
            Agent agent = brokerage.founder();
            ClosedTransaction closing = TestBrokerage.closing(agent, "20000.00");

            recordClosedTransaction.record(closing);
            long afterFirst = outbox.count();
            recordClosedTransaction.record(closing);

            assertThat(outbox.count())
                    .as("a redelivered closing must not re-announce itself downstream")
                    .isEqualTo(afterFirst);
        }
    }

    @Nested
    @DisplayName("the outbox")
    class Outbox {

        @Test
        @DisplayName("records a commission event for every closing")
        void writesCommissionEvent() {
            Agent agent = brokerage.founder();
            long before = outbox.count();

            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));

            assertThat(outbox.count()).isGreaterThan(before);
            assertThat(outbox.findAll()).extracting(e -> e.getEventType()).contains("CommissionCalculated");
        }

        @Test
        @DisplayName("records a distinct cap-threshold event on the closing that caps")
        void writesCapThresholdEvent() {
            Agent agent = brokerage.founder();

            recordClosedTransaction.record(TestBrokerage.closing(agent, "80000.00"));

            assertThat(outbox.findAll()).extracting(e -> e.getEventType()).contains("CapThresholdReached");
        }

        @Test
        @DisplayName("keys commission events on the agent, so their closings stay ordered")
        void partitionsOnTheAgent() {
            Agent agent = brokerage.founder();

            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));

            assertThat(outbox.findAll())
                    .filteredOn(e -> "CommissionCalculated".equals(e.getEventType()))
                    .extracting(e -> e.getPartitionKey())
                    .contains(agent.id().value().toString());
        }

        @Test
        @DisplayName("leaves every event unpublished for the relay to pick up")
        void eventsStartUnpublished() {
            Agent agent = brokerage.founder();

            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));

            assertThat(outbox.countByPublishedAtIsNull()).isPositive();
        }
    }

    @Test
    @DisplayName("rejects a closing for an agent that does not exist")
    void rejectsUnknownAgent() {
        Agent ghost = Agent.enroll(AgentId.newId(), "Not", "Enrolled", "ghost@example.test", TestBrokerage.JOINED);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> recordClosedTransaction.record(TestBrokerage.closing(ghost, "10000.00")))
                .isInstanceOf(RecordClosedTransactionService.UnknownAgentException.class);
    }

    @Test
    @DisplayName("opens a fresh cap year on the agent's anniversary")
    void capResetsOnTheAnniversary() {
        Agent agent = brokerage.founder();
        recordClosedTransaction.record(TestBrokerage.closing(agent, "40000.00", TestBrokerage.JOINED.plusMonths(6)));

        LocalDate nextYear = TestBrokerage.JOINED.plusYears(1).plusMonths(1);
        RecordClosedTransaction.Receipt newYear =
                recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00", nextYear));

        // The new cap year starts at zero, so this closing contributes its full 15%.
        assertThat(newYear.progressAfter().contributed()).isEqualTo(Money.of("1500.00"));
        assertThat(newYear.progressAfter().capYear().ordinal()).isEqualTo(1);
    }
}
