package com.revshare.domain.commission;

import com.revshare.domain.agent.EliteStatus;
import com.revshare.domain.shared.Money;
import com.revshare.domain.shared.Percentage;
import java.util.Objects;

/**
 * The commission schedule an agent is compensated under: the split, the annual cap, and the flat fee that replaces the
 * split once the cap is reached.
 *
 * <p>A value object rather than a constants file, because these figures are versioned business policy. Recomputing a
 * two-year-old statement has to use the schedule that was in force when the deal closed, not today's, so the plan is
 * passed into the calculation explicitly and every result can be traced back to the schedule that produced it.
 *
 * <p>Only the company's share is stored. The agent's share is derived as its complement, which makes "the two halves
 * sum to 100%" true by construction instead of an invariant someone has to remember to assert.
 */
public record CommissionPlan(
        Percentage companySplit, Money annualCap, Money postCapFeeStandard, Money postCapFeeElite) {

    public CommissionPlan {
        Objects.requireNonNull(companySplit, "companySplit must not be null");
        Objects.requireNonNull(annualCap, "annualCap must not be null");
        Objects.requireNonNull(postCapFeeStandard, "postCapFeeStandard must not be null");
        Objects.requireNonNull(postCapFeeElite, "postCapFeeElite must not be null");

        if (companySplit.isZero() || companySplit.compareTo(Percentage.ONE_HUNDRED_PERCENT) >= 0) {
            throw new IllegalArgumentException(
                    "company split must be between 0% and 100% exclusive, was " + companySplit);
        }
        if (!annualCap.isPositive()) {
            throw new IllegalArgumentException("annual cap must be positive, was " + annualCap);
        }
        if (postCapFeeStandard.isNegative() || postCapFeeElite.isNegative()) {
            throw new IllegalArgumentException("post-cap fees must not be negative");
        }
        if (postCapFeeElite.isGreaterThan(postCapFeeStandard)) {
            throw new IllegalArgumentException(
                    "elite fee " + postCapFeeElite + " should not exceed the standard fee " + postCapFeeStandard);
        }
    }

    /**
     * The published schedule this simulator models: an 85/15 split, a $12,000 annual cap, and a $285 per-transaction
     * fee after capping, reduced to $129 for Elite agents.
     */
    public static CommissionPlan standard() {
        return new CommissionPlan(
                Percentage.ofPercent("15"), Money.of("12000.00"), Money.of("285.00"), Money.of("129.00"));
    }

    /** The agent's share of gross commission before capping. 85% under the standard plan. */
    public Percentage agentSplit() {
        return companySplit.complement();
    }

    /**
     * How much gross commission an agent must produce in a cap year to reach the cap.
     *
     * <p>Under the standard plan: $12,000 / 15% = $80,000.
     *
     * <p>This derived figure is load-bearing well beyond cap tracking. It is the ceiling on how much of one agent's
     * production can ever generate revenue share in a year, and so it is what the published per-tier annual maxima are
     * actually computed from. See {@code RevenueSharePlan#annualMaximumPerContributor}.
     */
    public Money grossCommissionRequiredToCap() {
        return annualCap.dividedBy(companySplit);
    }

    /** The flat fee charged on transactions closed after the agent has capped. */
    public Money postCapFee(EliteStatus eliteStatus) {
        Objects.requireNonNull(eliteStatus, "eliteStatus must not be null");
        return eliteStatus == EliteStatus.ELITE ? postCapFeeElite : postCapFeeStandard;
    }

    @Override
    public String toString() {
        return "CommissionPlan[" + agentSplit() + "/" + companySplit
                + " split, " + annualCap + " cap, post-cap fee " + postCapFeeStandard
                + " (" + postCapFeeElite + " elite)]";
    }
}
