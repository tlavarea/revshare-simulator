package com.revshare.seed;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.commission.CommissionPlan;
import com.revshare.domain.revshare.ProducingAgentPolicy;
import com.revshare.domain.transaction.ClosedTransaction;
import java.util.List;
import java.util.Objects;

/**
 * A complete synthetic brokerage: the roster, the sponsorship tree it forms, and the stream of closings its agents
 * produced.
 *
 * <p>Entirely fabricated. No figure here originates from a real brokerage, a real agent, or a real sale; the names are
 * drawn from generic word lists, the addresses are opaque identifiers, and the email domain is one the standards
 * reserve so it can never resolve. What is modeled on reality is the <em>shape</em> of the data: the skew of agent
 * production, the depth of referral chains, and the seasonality of closings.
 *
 * <p>Deterministic. {@link SeedConfig#randomSeed()} fixes the output completely, so the same configuration regenerates
 * the same brokerage, down to individual UUIDs, on any machine. That is what lets generated data be committed to a test
 * as a fixture, or regenerated in CI instead of stored.
 */
public record BrokerageSeed(
        SeedConfig config, List<Agent> agents, List<ClosedTransaction> transactions, SeedSummary summary) {

    public BrokerageSeed {
        Objects.requireNonNull(config, "config must not be null");
        agents = List.copyOf(agents);
        transactions = List.copyOf(transactions);
        Objects.requireNonNull(summary, "summary must not be null");
    }

    /** Generates a brokerage and measures what the domain rules make of it. */
    public static BrokerageSeed generate(SeedConfig config) {
        return generate(config, CommissionPlan.standard(), ProducingAgentPolicy.standard());
    }

    /**
     * Generates against explicit schedules, so a caller can produce a fixture for a plan other than the standard one.
     *
     * <p>The generator draws from a single {@link SeedRandom} threaded through both stages in a fixed order. Roster
     * first, transactions second; reordering them, or giving either stage its own generator, changes the output for an
     * unchanged seed.
     */
    public static BrokerageSeed generate(SeedConfig config, CommissionPlan plan, ProducingAgentPolicy producingPolicy) {

        SeedRandom random = new SeedRandom(config.randomSeed());

        List<Agent> roster = new AgentRosterGenerator(config, random).generate();
        List<ClosedTransaction> transactions = new TransactionStreamGenerator(config, random).generate(roster);

        SeedSummary summary = SeedSummary.of(roster, transactions, plan, producingPolicy, config.simulationEnd());

        return new BrokerageSeed(config, roster, transactions, summary);
    }
}
