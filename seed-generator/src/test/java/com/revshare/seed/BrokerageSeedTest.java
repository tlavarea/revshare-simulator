package com.revshare.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.transaction.ClosedTransaction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BrokerageSeedTest {

    /** Small enough to keep the suite fast, large enough to grow a five-deep tree. */
    private static final SeedConfig SMALL = SeedConfig.defaults().withAgentCount(250);

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same seed regenerates an identical brokerage, down to the UUIDs")
        void sameSeedProducesIdenticalOutput() {
            BrokerageSeed first = BrokerageSeed.generate(SMALL);
            BrokerageSeed second = BrokerageSeed.generate(SMALL);

            assertThat(idsOf(first.agents())).isEqualTo(idsOf(second.agents()));
            assertThat(first.agents())
                    .extracting(Agent::email)
                    .isEqualTo(second.agents().stream().map(Agent::email).toList());
            assertThat(first.agents())
                    .extracting(Agent::joinedOn)
                    .isEqualTo(second.agents().stream().map(Agent::joinedOn).toList());
            assertThat(first.agents())
                    .extracting(Agent::terminatedOn)
                    .isEqualTo(second.agents().stream().map(Agent::terminatedOn).toList());

            // ClosedTransaction is a record of value objects, so equality covers every
            // field: ids, dates, prices and commissions all have to match exactly.
            assertThat(first.transactions()).isEqualTo(second.transactions());
            assertThat(first.summary()).isEqualTo(second.summary());
        }

        @Test
        @DisplayName("a different seed produces a different brokerage")
        void differentSeedProducesDifferentOutput() {
            BrokerageSeed first = BrokerageSeed.generate(SMALL);
            BrokerageSeed second = BrokerageSeed.generate(SMALL.withRandomSeed(987654321L));

            assertThat(idsOf(first.agents())).isNotEqualTo(idsOf(second.agents()));
        }

        private static List<AgentId> idsOf(List<Agent> agents) {
            return agents.stream().map(Agent::id).toList();
        }
    }

    @Nested
    @DisplayName("structural validity of the sponsorship tree")
    class TreeStructure {

        private final BrokerageSeed seed = BrokerageSeed.generate(SMALL);

        @Test
        @DisplayName("every sponsor joined no later than the agent they sponsored")
        void sponsorsPredateTheirRecruits() {
            Map<AgentId, Agent> byId = index(seed.agents());

            for (Agent agent : seed.agents()) {
                agent.sponsorId().ifPresent(sponsorId -> {
                    Agent sponsor = byId.get(sponsorId);
                    assertThat(sponsor).as("sponsor of %s exists", agent.id()).isNotNull();
                    assertThat(sponsor.joinedOn())
                            .as("%s was sponsored by someone who joined later", agent.email())
                            .isBeforeOrEqualTo(agent.joinedOn());
                });
            }
        }

        @Test
        @DisplayName("every agent's path is their sponsor's path with the sponsor prepended")
        void pathsExtendTheirSponsors() {
            Map<AgentId, Agent> byId = index(seed.agents());

            for (Agent agent : seed.agents()) {
                if (agent.sponsorshipPath().isRoot()) {
                    assertThat(agent.sponsorId()).isEmpty();
                    continue;
                }
                Agent sponsor = byId.get(agent.sponsorId().orElseThrow());
                List<AgentId> expected = new java.util.ArrayList<>();
                expected.add(sponsor.id());
                expected.addAll(sponsor.sponsorshipPath().ancestorsNearestFirst());

                assertThat(agent.sponsorshipPath().ancestorsNearestFirst()).isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("no agent appears in their own upline")
        void treeHasNoCycles() {
            for (Agent agent : seed.agents()) {
                assertThat(agent.sponsorshipPath().ancestorsNearestFirst())
                        .as("upline of %s", agent.email())
                        .doesNotContain(agent.id());
            }
        }

        @Test
        @DisplayName("founders are the only agents without a sponsor")
        void onlyFoundersAreRoots() {
            long roots = seed.agents().stream()
                    .filter(agent -> agent.sponsorshipPath().isRoot())
                    .count();

            assertThat(roots).isEqualTo(SMALL.founderCount());
        }
    }

    @Nested
    @DisplayName("transaction stream")
    class TransactionStream {

        private final BrokerageSeed seed = BrokerageSeed.generate(SMALL);

        @Test
        @DisplayName("is ordered by closing date, as a consumer would have received it")
        void isChronological() {
            assertThat(seed.transactions())
                    .extracting(ClosedTransaction::closedOn)
                    .isSorted();
        }

        @Test
        @DisplayName("contains no closing from before an agent joined or after they left")
        void closingsFallInsideAffiliationWindows() {
            Map<AgentId, Agent> byId = index(seed.agents());

            for (ClosedTransaction transaction : seed.transactions()) {
                Agent agent = byId.get(transaction.agentId());
                assertThat(agent).isNotNull();
                assertThat(agent.wasAffiliatedOn(transaction.closedOn()))
                        .as(
                                "%s closed %s on %s but was affiliated %s to %s",
                                agent.email(),
                                transaction.id(),
                                transaction.closedOn(),
                                agent.joinedOn(),
                                agent.terminatedOn().orElse(null))
                        .isTrue();
            }
        }

        @Test
        @DisplayName("stays inside the configured simulation window")
        void closingsFallInsideTheSimulationWindow() {
            assertThat(seed.transactions()).allSatisfy(transaction -> {
                assertThat(transaction.closedOn()).isAfterOrEqualTo(SMALL.simulationStart());
                assertThat(transaction.closedOn()).isBefore(SMALL.simulationEnd());
            });
        }

        @Test
        @DisplayName("gives every closing a positive commission no larger than the sale price")
        void amountsAreSane() {
            // Enforced by ClosedTransaction's constructor, so this is really a check that
            // the generator's lognormal draws cannot produce a degenerate deal.
            assertThat(seed.transactions()).allSatisfy(transaction -> {
                assertThat(transaction.grossCommissionIncome().isPositive()).isTrue();
                assertThat(transaction.grossCommissionIncome()).isLessThan(transaction.salePrice());
            });
        }

        @Test
        @DisplayName("uses unique identifiers throughout")
        void identifiersAreUnique() {
            assertThat(seed.transactions()).extracting(ClosedTransaction::id).doesNotHaveDuplicates();
            assertThat(seed.agents()).extracting(Agent::id).doesNotHaveDuplicates();
            assertThat(seed.agents()).extracting(Agent::email).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("fitness as a fixture")
    class FixtureFitness {

        // The point of these assertions is that a plausible-looking dataset can still be
        // useless. If a tuning change quietly stops producing capped agents, or stops
        // producing chains deep enough to reach tier 5, every downstream test that thinks
        // it exercises those rules would keep passing while testing nothing.

        private final BrokerageSeed seed = BrokerageSeed.generate(SeedConfig.defaults());
        private final SeedSummary summary = seed.summary();

        @Test
        @DisplayName("puts a realistic minority of agents over the cap")
        void capsARealisticMinority() {
            double cappedFraction = (double) summary.agentsWhoCapped() / summary.agents();

            assertThat(cappedFraction)
                    .as("fraction of agents capping at least once over the window")
                    .isBetween(0.15, 0.55);
        }

        @Test
        @DisplayName("exercises both sides of the cap")
        void populatesPreAndPostCapPaths() {
            assertThat(summary.capEvents()).isPositive();
            assertThat(summary.postCapTransactions()).isPositive();
            assertThat(summary.postCapTransactions()).isLessThan(summary.transactions() / 2);
        }

        @Test
        @DisplayName("produces closings that straddle the cap")
        void producesCapStraddlingClosings() {
            // The hardest branch in the commission calculator, and the one that is never
            // reached if every agent's production happens to land exactly on the cap.
            assertThat(summary.capStraddlingTransactions()).isPositive();
        }

        @Test
        @DisplayName("grows chains deep enough to reach tier 5")
        void growsChainsPastTheProgramDepth() {
            assertThat(summary.maxSponsorshipDepth()).isGreaterThanOrEqualTo(5);
            assertThat(summary.agentsWithFullFiveTierUpline()).isPositive();
        }

        @Test
        @DisplayName("produces departures from the middle of a chain")
        void producesMidChainDepartures() {
            // The configuration that distinguishes a correct downline implementation from
            // one that compresses the tree when a sponsor leaves.
            assertThat(summary.midChainDepartures()).isPositive();
        }

        @Test
        @DisplayName("produces agents on both sides of the producing-agent threshold")
        void populatesBothSidesOfTheProducingPolicy() {
            assertThat(summary.agentsFailingProducingPolicy()).isPositive();
            assertThat(summary.agentsFailingProducingPolicy()).isLessThan(summary.agents());
        }

        @Test
        @DisplayName("balances the books: agent earnings plus company dollar equal gross")
        void moneyIsConserved() {
            assertThat(summary.totalAgentEarnings().plus(summary.totalCompanyDollar()))
                    .isEqualTo(summary.totalGrossCommission());
        }

        @Test
        @DisplayName("issues only non-deliverable email addresses")
        void containsNoUsableContactDetails() {
            assertThat(seed.agents())
                    .allSatisfy(agent -> assertThat(agent.email()).endsWith("@example.test"));
        }
    }

    private static Map<AgentId, Agent> index(List<Agent> agents) {
        Map<AgentId, Agent> byId = new LinkedHashMap<>();
        agents.forEach(agent -> byId.put(agent.id(), agent));
        return byId;
    }
}
