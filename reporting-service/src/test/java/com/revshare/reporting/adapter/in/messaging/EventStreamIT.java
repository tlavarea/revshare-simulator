package com.revshare.reporting.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.event.CommissionCalculated;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.domain.transaction.TransactionId;
import com.revshare.reporting.AbstractStreamIT;
import com.revshare.reporting.TestEvents;
import com.revshare.reporting.adapter.out.mongo.AgentDashboardMongoRepository;
import com.revshare.reporting.adapter.out.mongo.document.AgentDashboardDocument;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The delivery path, end to end: a record on a topic becomes a dashboard in Mongo.
 *
 * <p>What this covers that {@code DashboardProjectionIT} cannot — the parts between the broker and the projector, all
 * of which are easy to get wrong and invisible to a test that calls the projector directly: the {@code event-type}
 * header dispatch, deserialisation of the write side's flattened wire format back into domain records, and the
 * listener's own configuration.
 *
 * <p>Deliberately thin on business assertions. Proving that a tier 3 award lands on the right dashboard is the
 * projection test's job; proving it survives a round trip through Kafka is this one's.
 */
@DisplayName("consuming the event stream")
class EventStreamIT extends AbstractStreamIT {

    @Autowired
    private AgentDashboardMongoRepository dashboards;

    private Optional<AgentDashboardDocument> dashboardOf(AgentId agent) {
        return dashboards.findById(agent.toString());
    }

    @Test
    void aPublishedClosingBecomesADashboard() {
        AgentId agent = TestEvents.agent();
        CommissionCalculated event =
                TestEvents.closing(agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00");

        publish(EventTopics.COMMISSION, event);

        await("the dashboard for " + agent, () -> dashboardOf(agent).isPresent());

        var dashboard = dashboardOf(agent).orElseThrow();
        assertThat(dashboard.getProduction().getClosings()).isEqualTo(1);
        assertThat(dashboard.getProduction().getGrossCommissionIncome()).isEqualByComparingTo("10000.00");
        // The figure that proves the payload survived the trip: Money went out as a JSON
        // number and came back an exact decimal, not a double.
        assertThat(dashboard.getCapProgress().getContributed()).isEqualByComparingTo("1500.00");
        assertThat(dashboard.getCapProgress().getCapYearStart()).isEqualTo(TestEvents.ANNIVERSARY);
    }

    @Test
    void revenueShareReachesEveryBeneficiaryInTheUpline() {
        List<AgentId> upline = List.of(TestEvents.agent(), TestEvents.agent(), TestEvents.agent());
        AgentId seller = TestEvents.agent();

        publish(EventTopics.REVENUE_SHARE, TestEvents.upline(upline, seller, TestEvents.transaction(), "80000.00"));

        await(
                "all three beneficiary dashboards",
                () -> upline.stream().allMatch(agent -> dashboardOf(agent).isPresent()));

        assertThat(dashboardOf(upline.get(0)).orElseThrow().getRevenueShare().getTotalAwarded())
                .isEqualByComparingTo("4000.00");
        assertThat(dashboardOf(upline.get(2)).orElseThrow().getRevenueShare().getTotalAwarded())
                .isEqualByComparingTo("2400.00");

        var tier3 = dashboardOf(upline.get(2))
                .orElseThrow()
                .getRevenueShare()
                .getByTier()
                .get(RevenueShareTier.TIER_3.name());
        assertThat(tier3.getContributors()).containsExactly(seller.toString());
    }

    @Test
    void cappingAnnouncementFollowsTheCommissionThatCausedIt() {
        AgentId agent = TestEvents.agent();
        TransactionId closing = TestEvents.transaction();
        LocalDate reachedOn = LocalDate.of(2025, 9, 22);

        // Both on the commission topic, keyed by the same agent, so they share a partition and
        // arrive in this order. That grouping is the whole reason EventTopics maps by aggregate
        // rather than by event type - split across topics the cap flag could land first, on a
        // dashboard with no closing to explain it.
        publish(EventTopics.COMMISSION, TestEvents.closing(agent, closing, reachedOn, "80000.00", "12000.00"));
        publish(EventTopics.COMMISSION, TestEvents.capped(agent, closing, reachedOn));

        await(
                "the capped flag",
                () -> dashboardOf(agent).map(d -> d.getCapProgress().isCapped()).orElse(false));

        var dashboard = dashboardOf(agent).orElseThrow();
        assertThat(dashboard.getCapProgress().getCappedOn()).isEqualTo(reachedOn);
        assertThat(dashboard.getCapProgress().getRemaining()).isEqualByComparingTo("0.00");
        assertThat(dashboard.getProduction().getClosings()).isEqualTo(1);
    }

    @Test
    void redeliveryOfTheSameRecordIsProjectedOnce() {
        AgentId agent = TestEvents.agent();
        CommissionCalculated event =
                TestEvents.closing(agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00");

        // The same event, published three times. This is exactly what an outbox relay that
        // crashed after sending but before marking the row published would produce.
        publish(EventTopics.COMMISSION, event);
        publish(EventTopics.COMMISSION, event);
        publish(EventTopics.COMMISSION, event);

        await("the dashboard for " + agent, () -> dashboardOf(agent).isPresent());

        // Give the two redeliveries time to be wrongly applied, so this test can fail rather
        // than racing past the bug it exists to catch.
        quietFor(1500);

        assertThat(dashboardOf(agent).orElseThrow().getProduction().getClosings())
                .isEqualTo(1);
    }

    @Test
    void anUnknownEventTypeIsSkippedWithoutStallingTheStream() {
        AgentId agent = TestEvents.agent();

        // A type from a future version of the write side. The read side has never heard of it
        // and must step over it: failing here would be a crash loop on a record no redeploy
        // of this service could get past.
        publishRaw(EventTopics.COMMISSION, agent.toString(), "{\"eventId\":\"x\"}", "AgentOnboarded");
        publish(
                EventTopics.COMMISSION,
                TestEvents.closing(agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00"));

        // The event behind the unknown one still lands, which is the actual assertion: the
        // consumer moved on rather than stopping the partition.
        await("the closing behind the unknown record", () -> dashboardOf(agent).isPresent());
        assertThat(dashboardOf(agent).orElseThrow().getProduction().getClosings())
                .isEqualTo(1);
    }

    @Test
    void aRecordWithNoEventTypeHeaderIsDropped() {
        AgentId agent = TestEvents.agent();

        publishRaw(EventTopics.COMMISSION, agent.toString(), "{\"eventId\":\"x\"}", null);
        publish(
                EventTopics.COMMISSION,
                TestEvents.closing(agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00"));

        await(
                "the closing behind the headerless record",
                () -> dashboardOf(agent).isPresent());
        assertThat(dashboardOf(agent).orElseThrow().getProduction().getClosings())
                .isEqualTo(1);
    }

    /** Waits, so that redeliveries have a chance to be wrongly applied before the assertion runs. */
    private static void quietFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
