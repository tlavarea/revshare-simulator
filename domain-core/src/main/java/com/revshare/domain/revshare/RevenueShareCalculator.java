package com.revshare.domain.revshare;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.SponsorshipPath;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Distributes the revenue share arising from one closing across the contributor's upline. Stateless domain service.
 *
 * <p>Pure, like {@link com.revshare.domain.commission.CommissionCalculator}: no ports, no clock, no repositories. Facts
 * about each beneficiary arrive pre-resolved as {@link BeneficiaryStanding}, so the whole rulebook is exercisable
 * without a single test double. See that type for why the lookups were pushed outward.
 *
 * <h2>The rules, in the order they are applied</h2>
 *
 * <ol>
 *   <li>Revenue share is assessed on the closing's revenue-share-eligible gross commission, not its full gross. A
 *       post-cap closing generates none, so no awards are produced.
 *   <li>Each ancestor's tier comes from their fixed position in the contributor's {@link SponsorshipPath}, never from
 *       who is currently active. A departure in the middle of the chain does not promote anyone below it.
 *   <li>A beneficiary who has left the brokerage forfeits.
 *   <li>A beneficiary whose tier is locked, for want of enough producing frontline agents, forfeits.
 *   <li>A beneficiary failing the Producing Agent Policy forfeits.
 *   <li>Otherwise the entitlement is paid, up to the tier's remaining annual allowance against this particular
 *       contributor.
 * </ol>
 *
 * <p>Forfeited amounts stay with the company. They are not redistributed to the next eligible ancestor, which keeps
 * every agent's earnings a function of their own downline and their own eligibility rather than of their upline's
 * circumstances.
 */
public final class RevenueShareCalculator {

    /**
     * @param split the priced closing that funds the distribution
     * @param contributorPath the contributor's frozen sponsorship path
     * @param standings resolved standing for every ancestor within program reach, keyed by agent id
     * @param plan the revenue share schedule, bound to the funding commission plan
     * @param producingPolicy the eligibility threshold applied to each beneficiary
     */
    public RevenueShareDistribution distribute(
            CommissionSplit split,
            SponsorshipPath contributorPath,
            Map<AgentId, BeneficiaryStanding> standings,
            RevenueSharePlan plan,
            ProducingAgentPolicy producingPolicy) {

        Objects.requireNonNull(split, "split must not be null");
        Objects.requireNonNull(contributorPath, "contributorPath must not be null");
        Objects.requireNonNull(standings, "standings must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(producingPolicy, "producingPolicy must not be null");

        Money eligibleGross = split.revenueShareEligibleGross();
        List<AgentId> upline = contributorPath.revenueShareUpline();

        // A post-cap closing produced no company dollar, so there is nothing to share out.
        // Returning early rather than emitting five zero-valued awards keeps the ledger from
        // filling with rows that say nothing happened.
        if (!eligibleGross.isPositive() || upline.isEmpty()) {
            return RevenueShareDistribution.none(
                    split.transactionId(), split.agentId(), split.closedOn(), eligibleGross);
        }

        List<RevenueShareAward> awards = new ArrayList<>(upline.size());

        for (int index = 0; index < upline.size(); index++) {
            AgentId beneficiary = upline.get(index);
            int depth = index + 1;

            RevenueShareTier tier = RevenueShareTier.atDepth(depth)
                    .orElseThrow(() -> new IllegalStateException("no tier defined at depth " + depth));

            BeneficiaryStanding standing = standings.get(beneficiary);
            if (standing == null) {
                // Fail loudly. Silently skipping an unresolved ancestor would under-pay a
                // real person, and would do it invisibly.
                throw new IllegalArgumentException("no standing supplied for upline agent " + beneficiary + " at "
                        + tier + " above contributor " + split.agentId());
            }

            awards.add(evaluate(split, beneficiary, tier, eligibleGross, standing, plan, producingPolicy));
        }

        return new RevenueShareDistribution(
                split.transactionId(), split.agentId(), split.closedOn(), eligibleGross, awards);
    }

    private RevenueShareAward evaluate(
            CommissionSplit split,
            AgentId beneficiary,
            RevenueShareTier tier,
            Money eligibleGross,
            BeneficiaryStanding standing,
            RevenueSharePlan plan,
            ProducingAgentPolicy producingPolicy) {

        Money entitlement = eligibleGross.multipliedBy(tier.rate());

        if (!standing.affiliated()) {
            return RevenueShareAward.forfeited(
                    beneficiary,
                    split.agentId(),
                    split.transactionId(),
                    tier,
                    eligibleGross,
                    entitlement,
                    ForfeitReason.BENEFICIARY_NOT_AFFILIATED);
        }

        Set<RevenueShareTier> unlocked = RevenueShareTier.unlockedFor(standing.producingFrontlineCount());
        if (!unlocked.contains(tier)) {
            return RevenueShareAward.forfeited(
                    beneficiary,
                    split.agentId(),
                    split.transactionId(),
                    tier,
                    eligibleGross,
                    entitlement,
                    ForfeitReason.TIER_LOCKED);
        }

        if (!producingPolicy.isSatisfiedBy(standing.trailingGrossCommission())) {
            return RevenueShareAward.forfeited(
                    beneficiary,
                    split.agentId(),
                    split.transactionId(),
                    tier,
                    eligibleGross,
                    entitlement,
                    ForfeitReason.BENEFICIARY_NOT_PRODUCING);
        }

        Money remainingAllowance =
                plan.annualMaximumPerContributor(tier).minus(standing.alreadyAwardedFromContributorThisCapYear());

        return RevenueShareAward.cappedAtAnnualMaximum(
                beneficiary,
                split.agentId(),
                split.transactionId(),
                tier,
                eligibleGross,
                entitlement,
                remainingAllowance);
    }
}
