package com.revshare.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.reporting.AbstractMongoIT;
import com.revshare.reporting.TestEvents;
import com.revshare.reporting.adapter.out.mongo.AgentDashboardMongoRepository;
import com.revshare.reporting.adapter.out.mongo.document.AgentDashboardDocument;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The org chart half of the projection, against a real Mongo.
 *
 * <p>This is the gap the agent lifecycle events were added to close. Before them the downline was assembled from
 * revenue share awards alone, so it was the <em>earning</em> downline: an agent who had been sponsored but had never
 * closed anything produced no award and appeared in nobody's dashboard. Accurate for the money, wrong as a roster, and
 * invisible either way — which is why it is tested rather than assumed.
 */
@DisplayName("projecting the downline roster")
class DownlineRosterProjectionIT extends AbstractMongoIT {

    private static final LocalDate JOINED = LocalDate.of(2025, 3, 14);

    @Autowired
    private DashboardProjector projector;

    @Autowired
    private AgentDashboardMongoRepository dashboards;

    private AgentDashboardDocument dashboardOf(AgentId agent) {
        return dashboards.findById(agent.toString()).orElseThrow();
    }

    private static AgentDashboardDocument.TierView tier(AgentDashboardDocument dashboard, RevenueShareTier tier) {
        return dashboard.getRevenueShare().getByTier().get(tier.name());
    }

    @Nested
    @DisplayName("enrolment")
    class Enrolment {

        @Test
        @DisplayName("puts an agent who has never sold anything in their sponsor's tier 1")
        void aNonProducerIsStillInTheDownline() {
            AgentId sponsor = TestEvents.agent();
            AgentId recruit = TestEvents.agent();

            projector.apply(TestEvents.enrolled(recruit, JOINED, sponsor));

            var tierOne = tier(dashboardOf(sponsor), RevenueShareTier.TIER_1);
            assertThat(tierOne.getDownlineCount()).isEqualTo(1);
            assertThat(tierOne.getDownline().get(recruit.toString()).getJoinedOn())
                    .isEqualTo(JOINED);

            // The whole point: present in the roster, absent from the earnings.
            assertThat(tierOne.getContributorCount()).isZero();
            assertThat(tierOne.getAwarded()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("places one enrolment at every depth of the upline at once")
        void oneEnrolmentFansOutToTheWholeUpline() {
            List<AgentId> upline =
                    List.of(TestEvents.agent(), TestEvents.agent(), TestEvents.agent(), TestEvents.agent());
            AgentId recruit = TestEvents.agent();

            projector.apply(TestEvents.enrolled(recruit, JOINED, upline.toArray(AgentId[]::new)));

            // Index 0 of the path is tier 1. Each ancestor sees the recruit at the depth that
            // matches their distance, from one event and with no tree walked.
            for (int index = 0; index < upline.size(); index++) {
                RevenueShareTier expected = RevenueShareTier.atDepth(index + 1).orElseThrow();
                var view = tier(dashboardOf(upline.get(index)), expected);
                assertThat(view.getDownline()).containsKey(recruit.toString());
            }
        }

        @Test
        @DisplayName("stops at the fifth tier, because there is no sixth to record it in")
        void reachStopsAtTheProgrammeDepth() {
            List<AgentId> upline = List.of(
                    TestEvents.agent(),
                    TestEvents.agent(),
                    TestEvents.agent(),
                    TestEvents.agent(),
                    TestEvents.agent(),
                    TestEvents.agent());
            AgentId recruit = TestEvents.agent();

            projector.apply(TestEvents.enrolled(recruit, JOINED, upline.toArray(AgentId[]::new)));

            // The sixth ancestor is real and is in the path — the hierarchy records true depth
            // independent of the payout schedule — but the dashboard is organised by the five
            // tiers of the programme and has nowhere to put them.
            assertThat(dashboards.findById(upline.get(5).toString())).isEmpty();
            assertThat(tier(dashboardOf(upline.get(4)), RevenueShareTier.TIER_5).getDownline())
                    .containsKey(recruit.toString());
        }

        @Test
        @DisplayName("gives the new agent their own dashboard before they have done anything")
        void theRecruitGetsADashboardOfTheirOwn() {
            AgentId sponsor = TestEvents.agent();
            AgentId recruit = TestEvents.agent();

            projector.apply(TestEvents.enrolled(recruit, JOINED, sponsor));

            // A change in what a 404 from the read API means: an enrolled agent who has closed
            // nothing now reads zero, and that zero is a fact rather than an assumption.
            var own = dashboardOf(recruit);
            assertThat(own.getAffiliation().getJoinedOn()).isEqualTo(JOINED);
            assertThat(own.getAffiliation().getSponsorId()).isEqualTo(sponsor.toString());
            assertThat(own.getAffiliation().isActive()).isTrue();
            assertThat(own.getProduction().getClosings()).isZero();
        }

        @Test
        @DisplayName("records no sponsor for an agent at the top of a tree")
        void anUnsponsoredAgentHasNoSponsor() {
            AgentId founder = TestEvents.agent();

            projector.apply(TestEvents.enrolledAtTheTop(founder, JOINED));

            assertThat(dashboardOf(founder).getAffiliation().getSponsorId()).isNull();
        }

        @Test
        @DisplayName("is idempotent, so a redelivery does not double the roster")
        void redeliveryDoesNotDuplicate() {
            AgentId sponsor = TestEvents.agent();
            AgentId recruit = TestEvents.agent();
            var enrolment = TestEvents.enrolled(recruit, JOINED, sponsor);

            assertThat(projector.apply(enrolment)).isTrue();
            assertThat(projector.apply(enrolment)).isFalse();

            assertThat(tier(dashboardOf(sponsor), RevenueShareTier.TIER_1).getDownlineCount())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("termination")
    class Termination {

        @Test
        @DisplayName("marks a member departed rather than removing them")
        void aDepartedMemberStaysInTheRoster() {
            AgentId sponsor = TestEvents.agent();
            AgentId leaver = TestEvents.agent();
            LocalDate left = LocalDate.of(2025, 9, 30);

            projector.apply(TestEvents.enrolled(leaver, JOINED, sponsor));
            projector.apply(TestEvents.terminated(leaver, left, sponsor));

            // The hierarchy does not compress. Removing the entry would assert a tree shape the
            // write side does not have, and would make the roster disagree with awards still
            // arriving through the departed agent from further down.
            var tierOne = tier(dashboardOf(sponsor), RevenueShareTier.TIER_1);
            assertThat(tierOne.getDownlineCount()).isEqualTo(1);
            assertThat(tierOne.getActiveDownlineCount()).isZero();
            assertThat(tierOne.getDownline().get(leaver.toString()).getTerminatedOn())
                    .isEqualTo(left);
        }

        @Test
        @DisplayName("marks the agent's own affiliation ended")
        void theirOwnDashboardShowsTheDeparture() {
            AgentId leaver = TestEvents.agent();
            LocalDate left = LocalDate.of(2025, 9, 30);

            projector.apply(TestEvents.enrolledAtTheTop(leaver, JOINED));
            projector.apply(TestEvents.terminated(leaver, left));

            assertThat(dashboardOf(leaver).getAffiliation().isActive()).isFalse();
            assertThat(dashboardOf(leaver).getAffiliation().getTerminatedOn()).isEqualTo(left);
        }

        @Test
        @DisplayName("does not promote anyone left behind")
        void theTreeDoesNotCompress() {
            // A sponsors B, B sponsors C. B leaves. C must stay at tier 2 beneath A.
            AgentId top = TestEvents.agent();
            AgentId middle = TestEvents.agent();
            AgentId bottom = TestEvents.agent();

            projector.apply(TestEvents.enrolled(middle, JOINED, top));
            projector.apply(TestEvents.enrolled(bottom, JOINED, middle, top));
            projector.apply(TestEvents.terminated(middle, LocalDate.of(2025, 9, 30), top));

            var topDashboard = dashboardOf(top);
            assertThat(tier(topDashboard, RevenueShareTier.TIER_1).getDownline())
                    .containsKey(middle.toString());
            assertThat(tier(topDashboard, RevenueShareTier.TIER_2).getDownline())
                    .as("the departure must not move C up a tier")
                    .containsKey(bottom.toString());
            assertThat(tier(topDashboard, RevenueShareTier.TIER_1).getDownline())
                    .doesNotContainKey(bottom.toString());
        }
    }

    @Nested
    @DisplayName("against the earning downline")
    class AgainstEarnings {

        @Test
        @DisplayName("counts a contributor once in each population")
        void aProducerIsBothADownlineMemberAndAContributor() {
            AgentId sponsor = TestEvents.agent();
            AgentId producer = TestEvents.agent();

            projector.apply(TestEvents.enrolled(producer, JOINED, sponsor));
            projector.apply(TestEvents.award(
                    sponsor, producer, TestEvents.transaction(), RevenueShareTier.TIER_1, "10000.00", "500.00"));

            var tierOne = tier(dashboardOf(sponsor), RevenueShareTier.TIER_1);
            assertThat(tierOne.getDownlineCount()).isEqualTo(1);
            assertThat(tierOne.getContributorCount()).isEqualTo(1);
            assertThat(tierOne.getAwarded()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("keeps contributors a subset of the downline even with no enrolment event")
        void anAwardAloneEstablishesMembership() {
            AgentId sponsor = TestEvents.agent();
            AgentId producer = TestEvents.agent();

            // No enrolment: the case of every agent who joined before the write side announced
            // enrolments at all. The award is itself proof of membership at this tier, so the
            // roster must not report fewer members than it has contributors.
            projector.apply(TestEvents.award(
                    sponsor, producer, TestEvents.transaction(), RevenueShareTier.TIER_1, "10000.00", "500.00"));

            var tierOne = tier(dashboardOf(sponsor), RevenueShareTier.TIER_1);
            assertThat(tierOne.getDownlineCount()).isEqualTo(1);
            assertThat(tierOne.getContributorCount()).isEqualTo(1);
            assertThat(tierOne.getDownline().get(producer.toString()).getJoinedOn())
                    .as("an award carries no join date, and inventing one would be worse than admitting it is unknown")
                    .isNull();
        }
    }
}
