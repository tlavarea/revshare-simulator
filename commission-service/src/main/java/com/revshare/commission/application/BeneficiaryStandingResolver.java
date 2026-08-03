package com.revshare.commission.application;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.port.out.AgentRepository;
import com.revshare.domain.port.out.ProductionHistory;
import com.revshare.domain.port.out.RevenueShareLedger;
import com.revshare.domain.revshare.BeneficiaryStanding;
import com.revshare.domain.revshare.ProducingAgentPolicy;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.domain.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers the facts {@code RevenueShareCalculator} needs about each potential beneficiary.
 *
 * <p>This class is the price of keeping the calculator pure, and it is deliberately where all the I/O concentrates. The
 * calculator decides what the facts <em>mean</em>; this decides how to fetch them without melting the database.
 *
 * <h2>Why the batching matters</h2>
 *
 * <p>Evaluating one closing needs, for each of up to five ancestors: whether they were affiliated, their own trailing
 * production, how many of <em>their</em> frontline agents are currently producing, and how much of the tier allowance
 * they have already drawn. The frontline is the dangerous one — a prolific sponsor can have hundreds of recruits, and
 * asking each one's trailing production individually would be hundreds of round trips per closing, five times over.
 *
 * <p>So every agent whose production is needed, the ancestors and all of their frontlines together, is collected first
 * and resolved in a single grouped aggregate.
 *
 * <p>All facts are resolved as at the closing date, never "now". Reprocessing a six-month-old closing has to reach the
 * verdict it reached originally.
 */
@Component
public class BeneficiaryStandingResolver {

    private final AgentRepository agents;
    private final ProductionHistory productionHistory;
    private final RevenueShareLedger ledger;
    private final ProducingAgentPolicy producingPolicy;

    public BeneficiaryStandingResolver(
            AgentRepository agents,
            ProductionHistory productionHistory,
            RevenueShareLedger ledger,
            ProducingAgentPolicy producingPolicy) {
        this.agents = agents;
        this.productionHistory = productionHistory;
        this.ledger = ledger;
        this.producingPolicy = producingPolicy;
    }

    /**
     * @param upline the contributor's ancestors within program reach, nearest first
     * @param contributorId whose production funds the distribution
     * @param closedOn the date every fact is resolved as at
     * @param contributorCapYear the allowance window, which belongs to the contributor
     */
    @Transactional(readOnly = true)
    public Map<AgentId, BeneficiaryStanding> resolve(
            List<AgentId> upline, AgentId contributorId, LocalDate closedOn, CapYear contributorCapYear) {

        if (upline.isEmpty()) {
            return Map.of();
        }

        LocalDate windowStart = producingPolicy.windowStart(closedOn);
        // Exclusive end one day past the closing, so a closing that settled the same day
        // counts toward the beneficiary's trailing production.
        LocalDate windowEnd = closedOn.plusDays(1);

        Map<AgentId, Agent> beneficiaries = agents.findRevenueShareUplineOf(contributorId);
        Map<AgentId, List<Agent>> frontlines = new LinkedHashMap<>();
        Set<AgentId> needProduction = new LinkedHashSet<>(upline);

        for (AgentId beneficiary : upline) {
            List<Agent> frontline = agents.findFrontlineOf(beneficiary);
            frontlines.put(beneficiary, frontline);
            frontline.forEach(agent -> needProduction.add(agent.id()));
        }

        // The one query that would otherwise be hundreds.
        Map<AgentId, Money> production =
                productionHistory.grossCommissionBetween(needProduction, windowStart, windowEnd);

        Map<AgentId, BeneficiaryStanding> standings = new LinkedHashMap<>();
        for (int index = 0; index < upline.size(); index++) {
            AgentId beneficiaryId = upline.get(index);
            Agent beneficiary = beneficiaries.get(beneficiaryId);

            RevenueShareTier tier = RevenueShareTier.atDepth(index + 1)
                    .orElseThrow(
                            () -> new IllegalStateException("no tier at depth " + (upline.indexOf(beneficiaryId) + 1)));

            standings.put(
                    beneficiaryId,
                    new BeneficiaryStanding(
                            beneficiaryId,
                            // A beneficiary missing from the roster is treated as not
                            // affiliated rather than skipped. Skipping would make the
                            // calculator throw; this records a forfeiture with a reason.
                            beneficiary != null && beneficiary.wasAffiliatedOn(closedOn),
                            production.getOrDefault(beneficiaryId, Money.ZERO),
                            countProducingFrontline(frontlines.get(beneficiaryId), production),
                            ledger.totalAwarded(beneficiaryId, contributorId, tier, contributorCapYear)));
        }

        return standings;
    }

    /**
     * How many of a beneficiary's personally-sponsored agents clear the production threshold, which is what unlocks
     * tiers 2 through 5.
     *
     * <p>Reads from the already-batched production map rather than querying per agent.
     */
    private int countProducingFrontline(List<Agent> frontline, Map<AgentId, Money> production) {
        if (frontline == null || frontline.isEmpty()) {
            return 0;
        }
        List<Agent> producing = new ArrayList<>();
        for (Agent agent : frontline) {
            Money trailing = production.getOrDefault(agent.id(), Money.ZERO);
            if (producingPolicy.isSatisfiedBy(trailing)) {
                producing.add(agent);
            }
        }
        return producing.size();
    }
}
