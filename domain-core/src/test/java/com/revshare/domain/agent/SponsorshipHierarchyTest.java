package com.revshare.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revshare.domain.revshare.RevenueShareDownline;
import com.revshare.domain.revshare.RevenueShareTier;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The hierarchy rules, which are the ones most likely to be got wrong by an implementation that walks live parent
 * pointers instead of a frozen path.
 */
class SponsorshipHierarchyTest {

    private static final LocalDate JOINED = LocalDate.of(2024, 1, 15);

    @Nested
    @DisplayName("tier resolution")
    class TierResolution {

        @Test
        @DisplayName("counts levels upward from the agent, 1-based")
        void resolvesTiersAlongTheChain() {
            Chain chain = Chain.of(3);

            SponsorshipPath deepest = chain.pathOf(2);
            assertThat(deepest.depth()).isEqualTo(2);
            assertThat(deepest.tierOf(chain.id(1))).hasValue(1);
            assertThat(deepest.tierOf(chain.id(0))).hasValue(2);
        }

        @Test
        @DisplayName("reports no tier for an agent outside the upline")
        void unrelatedAgentHasNoTier() {
            Chain chain = Chain.of(3);

            assertThat(chain.pathOf(2).tierOf(AgentId.newId())).isEmpty();
        }

        @Test
        @DisplayName("pays only five levels up, however deep the chain runs")
        void revenueShareReachesFiveLevels() {
            Chain chain = Chain.of(8);

            SponsorshipPath deepest = chain.pathOf(7);
            assertThat(deepest.depth()).isEqualTo(7);
            assertThat(deepest.revenueShareUpline()).hasSize(5);
            // The full ancestry is still recorded; only the payout reach is limited.
            assertThat(deepest.tierOf(chain.id(0))).hasValue(7);
            assertThat(RevenueShareTier.atDepth(7)).isEmpty();
        }

        @Test
        @DisplayName("refuses to enroll an agent under themselves")
        void rejectsSelfSponsorship() {
            AgentId id = AgentId.newId();

            assertThatThrownBy(() -> Agent.enrollSponsoredBy(
                            id, "Self", "Sponsor", "self@example.test", JOINED, id, SponsorshipPath.root()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot sponsor themselves");
        }

        @Test
        @DisplayName("refuses to create a cycle in the tree")
        void rejectsCycles() {
            Chain chain = Chain.of(3);
            AgentId root = chain.id(0);

            // Attempting to re-enroll the root beneath its own descendant.
            assertThatThrownBy(() -> Agent.enrollSponsoredBy(
                            root, "Cycle", "Maker", "cycle@example.test", JOINED, chain.id(2), chain.pathOf(2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycle");
        }
    }

    @Nested
    @DisplayName("when an agent in the middle of a chain leaves")
    class MidChainDeparture {

        @Test
        @DisplayName("the departing agent's own position is untouched")
        void departingAgentKeepsTheirPath() {
            Chain chain = Chain.of(3);
            Agent middle = chain.agent(1);
            SponsorshipPath before = middle.sponsorshipPath();

            middle.terminate(LocalDate.of(2025, 6, 1));

            assertThat(middle.status()).isEqualTo(AgentStatus.TERMINATED);
            assertThat(middle.sponsorshipPath()).isEqualTo(before);
        }

        @Test
        @DisplayName("the tree does not compress: everyone below stays at their original depth")
        void downlineDoesNotCompress() {
            // A sponsors B, B sponsors C. B leaves. The tempting behaviour is to promote C
            // into A's frontline, which would pay A the 5% tier 1 rate on C's production
            // instead of the correct 4% tier 2 rate. The frozen path prevents it.
            Chain chain = Chain.of(3);
            AgentId a = chain.id(0);
            AgentId c = chain.id(2);

            chain.agent(1).terminate(LocalDate.of(2025, 6, 1));

            assertThat(chain.pathOf(2).tierOf(a)).hasValue(2);
            assertThat(RevenueShareDownline.of(a, chain.organization()).membersAt(RevenueShareTier.TIER_1))
                    .doesNotContain(c);
            assertThat(RevenueShareDownline.of(a, chain.organization()).membersAt(RevenueShareTier.TIER_2))
                    .containsExactly(c);
        }

        @Test
        @DisplayName("the departed agent remains a structural link in everyone's downline")
        void departedAgentStaysInTheTree() {
            Chain chain = Chain.of(3);
            AgentId a = chain.id(0);
            AgentId b = chain.id(1);

            chain.agent(1).terminate(LocalDate.of(2025, 6, 1));

            RevenueShareDownline downlineOfA = RevenueShareDownline.of(a, chain.organization());
            assertThat(downlineOfA.frontline()).containsExactly(b);
            assertThat(downlineOfA.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("agents enrolled afterwards still land below the departed agent")
        void newEnrollmentsBelowADepartedAgentKeepTheirDepth() {
            Chain chain = Chain.of(3);
            AgentId a = chain.id(0);
            chain.agent(1).terminate(LocalDate.of(2025, 6, 1));

            // C, whose sponsor B has left, sponsors a new agent D.
            Agent d = Agent.enrollSponsoredBy(
                    AgentId.newId(),
                    "Dana",
                    "Nguyen",
                    "dana@example.test",
                    LocalDate.of(2025, 7, 1),
                    chain.id(2),
                    chain.pathOf(2));

            assertThat(d.sponsorshipPath().tierOf(a)).hasValue(3);
            assertThat(d.sponsorshipPath().depth()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("downline projection")
    class DownlineProjection {

        @Test
        @DisplayName("groups a branching organization into the right tiers")
        void groupsByTier() {
            AgentId root = AgentId.newId();
            Agent rootAgent = Agent.enroll(root, "Root", "Agent", "root@example.test", JOINED);

            Map<AgentId, SponsorshipPath> org = new LinkedHashMap<>();
            org.put(root, rootAgent.sponsorshipPath());

            // Three frontline agents, each with two of their own.
            for (int i = 0; i < 3; i++) {
                Agent frontline = Agent.enrollSponsoredBy(
                        AgentId.newId(),
                        "Front",
                        "Line" + i,
                        "f" + i + "@example.test",
                        JOINED,
                        root,
                        rootAgent.sponsorshipPath());
                org.put(frontline.id(), frontline.sponsorshipPath());

                for (int j = 0; j < 2; j++) {
                    Agent second = Agent.enrollSponsoredBy(
                            AgentId.newId(),
                            "Second",
                            "Level" + i + j,
                            "s" + i + j + "@example.test",
                            JOINED,
                            frontline.id(),
                            frontline.sponsorshipPath());
                    org.put(second.id(), second.sponsorshipPath());
                }
            }

            RevenueShareDownline downline = RevenueShareDownline.of(root, org);

            assertThat(downline.frontline()).hasSize(3);
            assertThat(downline.membersAt(RevenueShareTier.TIER_2)).hasSize(6);
            assertThat(downline.size()).isEqualTo(9);
        }

        @Test
        @DisplayName("excludes the beneficiary from their own downline")
        void excludesSelf() {
            Chain chain = Chain.of(3);

            assertThat(RevenueShareDownline.of(chain.id(0), chain.organization()).membersByTier().values().stream()
                            .flatMap(java.util.List::stream))
                    .doesNotContain(chain.id(0));
        }

        @Test
        @DisplayName("stops at five levels, matching the program's reach")
        void stopsAtFiveLevels() {
            Chain chain = Chain.of(8);

            RevenueShareDownline downline = RevenueShareDownline.of(chain.id(0), chain.organization());

            // Seven agents sit below the root, but only five are within reach.
            assertThat(downline.size()).isEqualTo(5);
            assertThat(downline.membersAt(RevenueShareTier.TIER_5)).containsExactly(chain.id(5));
        }
    }

    /** A straight chain of agents, each sponsored by the one before. */
    private record Chain(java.util.List<Agent> agents) {

        static Chain of(int length) {
            java.util.List<Agent> built = new java.util.ArrayList<>(length);
            Agent previous = Agent.enroll(AgentId.newId(), "Agent", "Zero", "a0@example.test", JOINED);
            built.add(previous);
            for (int i = 1; i < length; i++) {
                Agent next = Agent.enrollSponsoredBy(
                        AgentId.newId(),
                        "Agent",
                        "Number" + i,
                        "a" + i + "@example.test",
                        JOINED,
                        previous.id(),
                        previous.sponsorshipPath());
                built.add(next);
                previous = next;
            }
            return new Chain(built);
        }

        Agent agent(int index) {
            return agents.get(index);
        }

        AgentId id(int index) {
            return agents.get(index).id();
        }

        SponsorshipPath pathOf(int index) {
            return agents.get(index).sponsorshipPath();
        }

        Map<AgentId, SponsorshipPath> organization() {
            Map<AgentId, SponsorshipPath> org = new LinkedHashMap<>();
            agents.forEach(agent -> org.put(agent.id(), agent.sponsorshipPath()));
            return org;
        }
    }
}
