package com.revshare.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.event.CommissionCalculated;
import com.revshare.domain.event.RevenueShareDistributed;
import com.revshare.domain.revshare.ForfeitReason;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.domain.transaction.TransactionId;
import com.revshare.reporting.AbstractMongoIT;
import com.revshare.reporting.TestEvents;
import com.revshare.reporting.adapter.out.mongo.AgentDashboardMongoRepository;
import com.revshare.reporting.adapter.out.mongo.document.AgentDashboardDocument;
import com.revshare.reporting.adapter.out.mongo.document.ProcessedEventDocument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The projection rules, driven directly against a real Mongo.
 *
 * <p>No broker here on purpose. Kafka delivers the events but decides nothing about what they mean, so testing the fold
 * through a topic would add ten seconds of container startup and a polling loop to every assertion without covering a
 * single extra branch. {@code EventStreamIT} covers the delivery path once, properly.
 */
@DisplayName("projecting the agent dashboard")
class DashboardProjectionIT extends AbstractMongoIT {

    @Autowired
    private DashboardProjector projector;

    @Autowired
    private AgentDashboardMongoRepository dashboards;

    private AgentDashboardDocument dashboardOf(AgentId agent) {
        return dashboards.findById(agent.toString()).orElseThrow();
    }

    @Nested
    @DisplayName("cap progress")
    class CapProgress {

        @Test
        void firstClosingOpensTheDashboard() {
            AgentId agent = TestEvents.agent();

            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00"));

            var progress = dashboardOf(agent).getCapProgress();
            assertThat(progress.getContributed()).isEqualByComparingTo("1500.00");
            assertThat(progress.getCapAmount()).isEqualByComparingTo("12000.00");
            assertThat(progress.getRemaining()).isEqualByComparingTo("10500.00");
            assertThat(progress.isCapped()).isFalse();
        }

        @Test
        void capBalanceIsOverwrittenNotAccumulated() {
            AgentId agent = TestEvents.agent();

            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00"));
            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 5, 1), "10000.00", "3000.00"));

            // 3000, the balance the second event carried - not 1500 + 3000. The event holds the
            // post-state, so summing it would count every earlier closing again.
            assertThat(dashboardOf(agent).getCapProgress().getContributed()).isEqualByComparingTo("3000.00");
        }

        @Test
        void cappingIsRecordedWithTheDateItHappened() {
            AgentId agent = TestEvents.agent();
            TransactionId closing = TestEvents.transaction();
            LocalDate reachedOn = LocalDate.of(2025, 9, 22);

            projector.apply(TestEvents.closing(agent, closing, reachedOn, "20000.00", "12000.00"));
            projector.apply(TestEvents.capped(agent, closing, reachedOn));

            var progress = dashboardOf(agent).getCapProgress();
            assertThat(progress.isCapped()).isTrue();
            assertThat(progress.getCappedOn()).isEqualTo(reachedOn);
            assertThat(progress.getRemaining()).isEqualByComparingTo("0.00");
        }

        @Test
        void anniversaryRolloverClearsTheCappedFlag() {
            AgentId agent = TestEvents.agent();
            LocalDate lastYear = LocalDate.of(2025, 9, 22);

            projector.apply(TestEvents.closing(agent, TestEvents.transaction(), lastYear, "20000.00", "12000.00"));
            projector.apply(TestEvents.capped(agent, TestEvents.transaction(), lastYear));
            assertThat(dashboardOf(agent).getCapProgress().isCapped()).isTrue();

            // First closing of the next cap year. The agent owes the full cap again, and the
            // dashboard must stop claiming they are capped - the flag belongs to a cap year,
            // not to the agent.
            LocalDate thisYear = LocalDate.of(2026, 4, 1);
            projector.apply(TestEvents.closing(agent, TestEvents.transaction(), thisYear, "10000.00", "1500.00"));

            var progress = dashboardOf(agent).getCapProgress();
            assertThat(progress.isCapped()).isFalse();
            assertThat(progress.getCappedOn()).isNull();
            assertThat(progress.getCapYearOrdinal()).isEqualTo(1);
            assertThat(progress.getContributed()).isEqualByComparingTo("1500.00");
        }
    }

    @Nested
    @DisplayName("production")
    class Production {

        @Test
        void closingsAccumulate() {
            AgentId agent = TestEvents.agent();

            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00"));
            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 5, 1), "20000.00", "4500.00"));

            var production = dashboardOf(agent).getProduction();
            assertThat(production.getClosings()).isEqualTo(2);
            assertThat(production.getGrossCommissionIncome()).isEqualByComparingTo("30000.00");
            assertThat(production.getAgentEarnings()).isEqualByComparingTo("25500.00");
            assertThat(production.getCompanyDollarContributed()).isEqualByComparingTo("4500.00");
        }

        @Test
        void postCapClosingAddsProductionButNotCapContribution() {
            AgentId agent = TestEvents.agent();

            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "80000.00", "12000.00"));
            projector.apply(TestEvents.postCapClosing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 6, 1), "9000.00", "285.00"));

            var production = dashboardOf(agent).getProduction();
            assertThat(production.getClosings()).isEqualTo(2);
            assertThat(production.getGrossCommissionIncome()).isEqualByComparingTo("89000.00");
            assertThat(production.getPostCapFeesPaid()).isEqualByComparingTo("285.00");
            // Still exactly the cap. The flat fee is not company dollar and must not move it.
            assertThat(production.getCompanyDollarContributed()).isEqualByComparingTo("12000.00");
        }
    }

    @Nested
    @DisplayName("revenue share")
    class RevenueShare {

        @Test
        void awardLandsOnTheBeneficiaryNotTheContributor() {
            AgentId sponsor = TestEvents.agent();
            AgentId seller = TestEvents.agent();

            projector.apply(TestEvents.award(
                    sponsor, seller, TestEvents.transaction(), RevenueShareTier.TIER_1, "10000.00", "500.00"));

            assertThat(dashboardOf(sponsor).getRevenueShare().getTotalAwarded()).isEqualByComparingTo("500.00");
            // The seller earned the commission, not the revenue share. A dashboard that credited
            // the contributor would pay every agent their own upline's share.
            assertThat(dashboards.findById(seller.toString())).isEmpty();
        }

        @Test
        void oneDistributionFansOutAcrossTheWholeUpline() {
            List<AgentId> upline = List.of(
                    TestEvents.agent(), TestEvents.agent(), TestEvents.agent(), TestEvents.agent(), TestEvents.agent());
            AgentId seller = TestEvents.agent();

            projector.apply(TestEvents.upline(upline, seller, TestEvents.transaction(), "80000.00"));

            // 5% / 4% / 3% / 2% / 1% of 80,000, which is the published tier maximum at each depth.
            assertThat(dashboardOf(upline.get(0)).getRevenueShare().getTotalAwarded())
                    .isEqualByComparingTo("4000.00");
            assertThat(dashboardOf(upline.get(4)).getRevenueShare().getTotalAwarded())
                    .isEqualByComparingTo("800.00");

            var tier5 = dashboardOf(upline.get(4)).getRevenueShare().getByTier().get("TIER_5");
            assertThat(tier5.getContributors()).containsExactly(seller.toString());
        }

        @Test
        void downlineIsGroupedByTier() {
            AgentId sponsor = TestEvents.agent();
            AgentId frontline = TestEvents.agent();
            AgentId deeper = TestEvents.agent();

            projector.apply(TestEvents.award(
                    sponsor, frontline, TestEvents.transaction(), RevenueShareTier.TIER_1, "10000.00", "500.00"));
            projector.apply(TestEvents.award(
                    sponsor, deeper, TestEvents.transaction(), RevenueShareTier.TIER_2, "10000.00", "400.00"));

            var byTier = dashboardOf(sponsor).getRevenueShare().getByTier();
            assertThat(byTier.get("TIER_1").getContributors()).containsExactly(frontline.toString());
            assertThat(byTier.get("TIER_2").getContributors()).containsExactly(deeper.toString());
            assertThat(dashboardOf(sponsor).getRevenueShare().getTotalAwarded()).isEqualByComparingTo("900.00");
        }

        @Test
        void repeatContributorIsCountedOnceInTheDownline() {
            AgentId sponsor = TestEvents.agent();
            AgentId seller = TestEvents.agent();

            projector.apply(TestEvents.award(
                    sponsor, seller, TestEvents.transaction(), RevenueShareTier.TIER_1, "10000.00", "500.00"));
            projector.apply(TestEvents.award(
                    sponsor, seller, TestEvents.transaction(), RevenueShareTier.TIER_1, "20000.00", "1000.00"));

            var tier1 = dashboardOf(sponsor).getRevenueShare().getByTier().get("TIER_1");
            assertThat(tier1.getContributorCount()).isEqualTo(1);
            // Earnings still accumulate; only the downline membership is a set.
            assertThat(tier1.getAwarded()).isEqualByComparingTo("1500.00");
        }

        @Test
        void forfeitedAwardStillPlacesTheContributorInTheDownline() {
            AgentId sponsor = TestEvents.agent();
            AgentId seller = TestEvents.agent();

            projector.apply(TestEvents.forfeited(
                    sponsor,
                    seller,
                    TestEvents.transaction(),
                    RevenueShareTier.TIER_1,
                    "10000.00",
                    "500.00",
                    ForfeitReason.BENEFICIARY_NOT_PRODUCING));

            var revenueShare = dashboardOf(sponsor).getRevenueShare();
            assertThat(revenueShare.getTotalAwarded()).isEqualByComparingTo("0.00");
            // The figure that tells the agent what failing the Producing Agent Policy cost them.
            assertThat(revenueShare.getTotalForfeited()).isEqualByComparingTo("500.00");
            assertThat(revenueShare.getByTier().get("TIER_1").getContributors()).containsExactly(seller.toString());
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        void redeliveredClosingIsNotCountedTwice() {
            AgentId agent = TestEvents.agent();
            CommissionCalculated event = TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00");

            assertThat(projector.apply(event)).isTrue();
            assertThat(projector.apply(event)).isFalse();

            var production = dashboardOf(agent).getProduction();
            assertThat(production.getClosings()).isEqualTo(1);
            assertThat(production.getGrossCommissionIncome()).isEqualByComparingTo("10000.00");
        }

        @Test
        void redeliveredDistributionDoesNotDoublePayTheUpline() {
            AgentId sponsor = TestEvents.agent();
            RevenueShareDistributed event = TestEvents.award(
                    sponsor,
                    TestEvents.agent(),
                    TestEvents.transaction(),
                    RevenueShareTier.TIER_1,
                    "10000.00",
                    "500.00");

            projector.apply(event);
            projector.apply(event);
            projector.apply(event);

            assertThat(dashboardOf(sponsor).getRevenueShare().getTotalAwarded()).isEqualByComparingTo("500.00");
        }

        @Test
        void everyAppliedEventIsMarkedProcessed() {
            AgentId agent = TestEvents.agent();

            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00"));
            projector.apply(TestEvents.capped(agent, TestEvents.transaction(), LocalDate.of(2025, 9, 22)));

            assertThat(mongo.findAll(ProcessedEventDocument.class))
                    .hasSize(2)
                    .extracting(ProcessedEventDocument::getEventType)
                    .containsExactlyInAnyOrder("CommissionCalculated", "CapThresholdReached");
        }

        @Test
        void distinctEventsForOneAgentAreAllApplied() {
            AgentId agent = TestEvents.agent();

            // Same agent, same figures, different event ids. Deduplication keys on the event,
            // not on the shape of what it says, so both must land.
            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00"));
            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "3000.00"));

            assertThat(dashboardOf(agent).getProduction().getClosings()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("storage")
    class Storage {

        @Test
        void moneyIsStoredAsDecimal128NotAsADouble() {
            AgentId agent = TestEvents.agent();
            projector.apply(TestEvents.closing(
                    agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.10", "1500.02"));

            org.bson.Document raw = mongo.getCollection("agent_dashboard")
                    .find(new org.bson.Document("_id", agent.toString()))
                    .first();

            Object contributed = raw.get("capProgress", org.bson.Document.class).get("contributed");

            // The assertion that matters is the type, not the value. Stored as a BSON double,
            // 1500.02 would read back as 1500.0200000000000954969436135889 and every total
            // built on it would drift; stored as a string it would be exact but unusable in an
            // aggregation.
            assertThat(contributed).isInstanceOf(org.bson.types.Decimal128.class);
            assertThat(((org.bson.types.Decimal128) contributed).bigDecimalValue())
                    .isEqualByComparingTo(new BigDecimal("1500.02"));
        }
    }
}
