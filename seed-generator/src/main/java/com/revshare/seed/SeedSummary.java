package com.revshare.seed;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionCalculator;
import com.revshare.domain.commission.CommissionPlan;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.revshare.ProducingAgentPolicy;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Statistics describing a generated brokerage, computed by running the real domain calculators over the generated data.
 *
 * <p>This is not decoration. A synthetic fixture is only useful if it actually reaches the states the rules are about,
 * and that is easy to get wrong: tune the production distribution slightly too low and no agent ever caps, so every
 * cap-related branch in the system goes permanently untested against realistic data. Slightly too high and every agent
 * caps in their first quarter, so the pre-cap split is barely exercised. Neither failure is visible by looking at the
 * generated JSON.
 *
 * <p>So the generator reports what its own output does when put through {@link CommissionCalculator}, and
 * {@code BrokerageSeedTest} asserts that the headline figures stay in a sensible band. The generator is a fixture with
 * a fitness test attached.
 */
public record SeedSummary(
        int agents,
        int founders,
        int terminatedAgents,
        int midChainDepartures,
        int maxSponsorshipDepth,
        int agentsWithFullFiveTierUpline,
        int transactions,
        Money totalGrossCommission,
        int agentsWhoCapped,
        int capEvents,
        int capStraddlingTransactions,
        int postCapTransactions,
        Money totalCompanyDollar,
        Money totalAgentEarnings,
        Money revenueShareEligibleGross,
        int agentsFailingProducingPolicy) {

    /**
     * Walks every agent's transactions in date order, pricing each one exactly as the write side would, and tallies
     * what happened.
     *
     * @param asOf the date the trailing-production check is evaluated against, normally the end of the simulation
     *     window
     */
    public static SeedSummary of(
            List<Agent> roster,
            List<ClosedTransaction> transactions,
            CommissionPlan plan,
            ProducingAgentPolicy producingPolicy,
            LocalDate asOf) {

        CommissionCalculator calculator = new CommissionCalculator();
        Map<AgentId, Agent> byId = new LinkedHashMap<>();
        roster.forEach(agent -> byId.put(agent.id(), agent));

        Map<AgentId, List<ClosedTransaction>> byAgent = new LinkedHashMap<>();
        for (ClosedTransaction transaction : transactions) {
            byAgent.computeIfAbsent(transaction.agentId(), id -> new java.util.ArrayList<>())
                    .add(transaction);
        }

        Set<AgentId> capped = new HashSet<>();
        int capEvents = 0;
        int straddles = 0;
        int postCap = 0;
        Money companyDollar = Money.ZERO;
        Money agentEarnings = Money.ZERO;
        Money grossTotal = Money.ZERO;
        Money eligibleGross = Money.ZERO;

        for (Map.Entry<AgentId, List<ClosedTransaction>> entry : byAgent.entrySet()) {
            Agent agent = byId.get(entry.getKey());
            // Cap progress is per cap year, and an agent's transactions span several. The
            // map is what makes the anniversary reset happen: a closing in a new cap year
            // finds no progress and opens a fresh one at zero.
            Map<CapYear, CapProgress> progressByCapYear = new HashMap<>();

            for (ClosedTransaction transaction : entry.getValue()) {
                CapYear capYear = agent.capYearOn(transaction.closedOn());
                CapProgress before =
                        progressByCapYear.computeIfAbsent(capYear, year -> CapProgress.opening(agent.id(), year, plan));

                CommissionCalculator.CommissionResult result =
                        calculator.calculate(transaction, before, plan, agent.eliteStatus());
                progressByCapYear.put(capYear, result.progressAfter());

                CommissionSplit split = result.split();
                companyDollar = companyDollar.plus(split.companyEarnings());
                agentEarnings = agentEarnings.plus(split.agentEarnings());
                grossTotal = grossTotal.plus(split.grossCommissionIncome());
                eligibleGross = eligibleGross.plus(split.revenueShareEligibleGross());

                if (split.pricedUnderPostCapFee()) {
                    postCap++;
                }
                if (split.reachedCapOnThisTransaction()) {
                    capEvents++;
                    capped.add(agent.id());
                    Money fullShare = split.grossCommissionIncome().multipliedBy(plan.companySplit());
                    if (split.capContribution().isLessThan(fullShare)) {
                        straddles++;
                    }
                }
            }
        }

        return new SeedSummary(
                roster.size(),
                (int) roster.stream().filter(a -> a.sponsorshipPath().isRoot()).count(),
                (int) roster.stream().filter(a -> a.terminatedOn().isPresent()).count(),
                countMidChainDepartures(roster),
                roster.stream().mapToInt(a -> a.sponsorshipPath().depth()).max().orElse(0),
                (int) roster.stream()
                        .filter(a -> a.sponsorshipPath().depth() >= 5)
                        .count(),
                transactions.size(),
                grossTotal,
                capped.size(),
                capEvents,
                straddles,
                postCap,
                companyDollar,
                agentEarnings,
                eligibleGross,
                countAgentsFailingProducingPolicy(roster, byAgent, producingPolicy, asOf));
    }

    /**
     * Terminated agents who have both an upline and a downline.
     *
     * <p>The single most important property of the fixture. This is the exact configuration that distinguishes a
     * correct downline implementation from one that compresses the tree on departure, and if the generator produces
     * none of them, nothing downstream can demonstrate the rule.
     */
    private static int countMidChainDepartures(List<Agent> roster) {
        Set<AgentId> sponsors = new HashSet<>();
        roster.forEach(agent -> agent.sponsorId().ifPresent(sponsors::add));

        return (int) roster.stream()
                .filter(agent -> agent.terminatedOn().isPresent())
                .filter(agent -> !agent.sponsorshipPath().isRoot())
                .filter(agent -> sponsors.contains(agent.id()))
                .count();
    }

    private static int countAgentsFailingProducingPolicy(
            List<Agent> roster,
            Map<AgentId, List<ClosedTransaction>> byAgent,
            ProducingAgentPolicy policy,
            LocalDate asOf) {

        LocalDate windowStart = policy.windowStart(asOf);

        return (int) roster.stream()
                .filter(agent -> agent.wasAffiliatedOn(asOf.minusDays(1)))
                .filter(agent -> {
                    Money trailing = byAgent.getOrDefault(agent.id(), List.of()).stream()
                            .filter(t -> !t.closedOn().isBefore(windowStart)
                                    && t.closedOn().isBefore(asOf))
                            .map(ClosedTransaction::grossCommissionIncome)
                            .reduce(Money.ZERO, Money::plus);
                    return !policy.isSatisfiedBy(trailing);
                })
                .count();
    }

    /** A human-readable report, printed by the CLI. */
    public String render() {
        return """
                Roster
                  agents                          %,d
                  founders (no sponsor)           %,d
                  terminated                      %,d
                  mid-chain departures            %,d   <- upline and downline both present
                  deepest sponsorship chain       %,d levels
                  agents 5+ levels deep           %,d

                Transactions
                  closings                        %,d
                  gross commission income         %s

                Commission outcomes
                  agents who capped at least once %,d
                  cap events (agent x cap year)   %,d
                  closings straddling the cap     %,d
                  post-cap closings (flat fee)    %,d
                  company dollar collected        %s
                  agent earnings                  %s

                Revenue share inputs
                  revenue-share-eligible gross    %s
                  agents below the $450 threshold %,d
                """.formatted(
                        agents,
                        founders,
                        terminatedAgents,
                        midChainDepartures,
                        maxSponsorshipDepth,
                        agentsWithFullFiveTierUpline,
                        transactions,
                        totalGrossCommission,
                        agentsWhoCapped,
                        capEvents,
                        capStraddlingTransactions,
                        postCapTransactions,
                        totalCompanyDollar,
                        totalAgentEarnings,
                        revenueShareEligibleGross,
                        agentsFailingProducingPolicy);
    }
}
