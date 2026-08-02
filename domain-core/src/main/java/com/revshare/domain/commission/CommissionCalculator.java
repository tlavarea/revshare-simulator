package com.revshare.domain.commission;

import com.revshare.domain.agent.EliteStatus;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import java.util.Objects;

/**
 * Applies a {@link CommissionPlan} to a closing. Stateless domain service.
 *
 * <p>A pure function of its arguments, with no repository access and no clock. Everything it needs is passed in, which
 * means the entire commission rulebook can be exercised in plain unit tests with no test doubles, and the same inputs
 * always produce the same output no matter when or where it runs.
 *
 * <h2>Modeled behavior</h2>
 *
 * <p>Three cases, in the order they occur over an agent's cap year:
 *
 * <ol>
 *   <li><strong>Below cap.</strong> The company takes its split percentage of gross commission, all of which counts
 *       toward the cap. The agent takes the rest.
 *   <li><strong>Crossing the cap.</strong> The company takes only what is left of the cap, not the full split. The
 *       remainder that would have been the company's share is paid to the agent instead. It does not disappear, and it
 *       is not carried forward.
 *   <li><strong>Above cap.</strong> The split no longer applies. The company takes a flat per-transaction fee, reduced
 *       for Elite agents, and the agent keeps the rest.
 * </ol>
 *
 * <h2>Documented assumptions</h2>
 *
 * <p>Two points the published schedule does not settle, resolved here in the agent's favor and called out so a reviewer
 * can see they were decisions rather than oversights:
 *
 * <ul>
 *   <li>The transaction that <em>crosses</em> the cap is priced under the split, not the flat fee. The fee begins on
 *       the next transaction. Charging both on a single closing would take more than the cap in that cap year.
 *   <li>The flat fee is clamped to the gross commission, so an unusually small closing after capping cannot produce
 *       negative agent earnings.
 * </ul>
 */
public final class CommissionCalculator {

    /**
     * Prices one closing.
     *
     * @param transaction the closing to price
     * @param progressBefore the agent's cap progress as at immediately before this closing
     * @param plan the schedule in force when the transaction closed
     * @param eliteStatus the agent's status, which selects the post-cap fee
     * @return the split and the cap progress that results from applying it
     */
    public CommissionResult calculate(
            ClosedTransaction transaction, CapProgress progressBefore, CommissionPlan plan, EliteStatus eliteStatus) {

        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(progressBefore, "progressBefore must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(eliteStatus, "eliteStatus must not be null");

        if (!progressBefore.agentId().equals(transaction.agentId())) {
            throw new IllegalArgumentException("cap progress belongs to agent " + progressBefore.agentId()
                    + " but the transaction belongs to " + transaction.agentId());
        }
        // Guards against the most damaging silent error in this system: applying a closing
        // to the wrong cap year, which would both understate the current year's cap and
        // corrupt a prior year's already-published statement.
        if (!progressBefore.capYear().contains(transaction.closedOn())) {
            throw new IllegalArgumentException("transaction closed " + transaction.closedOn()
                    + " does not fall within cap year " + progressBefore.capYear());
        }

        Money gross = transaction.grossCommissionIncome();

        return progressBefore.isCapped()
                ? pricePostCap(transaction, progressBefore, plan, eliteStatus, gross)
                : priceAgainstCap(transaction, progressBefore, plan, gross);
    }

    /** Case 3: the agent capped on an earlier transaction, so a flat fee applies. */
    private CommissionResult pricePostCap(
            ClosedTransaction transaction,
            CapProgress progress,
            CommissionPlan plan,
            EliteStatus eliteStatus,
            Money gross) {

        Money fee = Money.min(plan.postCapFee(eliteStatus), gross);

        CommissionSplit split = new CommissionSplit(
                transaction.id(),
                transaction.agentId(),
                transaction.closedOn(),
                gross,
                gross.minus(fee),
                fee,
                Money.ZERO,
                fee,
                Money.ZERO,
                true,
                false);

        return new CommissionResult(split, progress);
    }

    /** Cases 1 and 2: the split applies, clamped by whatever is left of the cap. */
    private CommissionResult priceAgainstCap(
            ClosedTransaction transaction, CapProgress progressBefore, CommissionPlan plan, Money gross) {

        Money fullCompanyShare = gross.multipliedBy(plan.companySplit());
        Money remaining = progressBefore.remaining();

        // The clamp. Everything above the remaining cap belongs to the agent.
        Money capContribution = Money.min(fullCompanyShare, remaining);
        Money agentEarnings = gross.minus(capContribution);
        boolean straddlesCap = capContribution.isLessThan(fullCompanyShare);

        // Revenue share is assessed on gross, but only the slice that produced company
        // dollar funds it. Below the cap that is the whole closing; when the closing
        // straddles the cap it is the fraction that landed before the crossing, recovered
        // by inverting the split. Clamped because that division rounds to cents.
        Money revenueShareEligibleGross =
                straddlesCap ? Money.min(gross, capContribution.dividedBy(plan.companySplit())) : gross;

        CapProgress progressAfter = progressBefore.withContribution(capContribution);

        CommissionSplit split = new CommissionSplit(
                transaction.id(),
                transaction.agentId(),
                transaction.closedOn(),
                gross,
                agentEarnings,
                capContribution,
                capContribution,
                Money.ZERO,
                revenueShareEligibleGross,
                false,
                progressAfter.isCapped());

        return new CommissionResult(split, progressAfter);
    }

    /**
     * The split and the cap progress it produces, returned together.
     *
     * <p>Paired deliberately. The two are only ever correct with respect to one another, and handing back a split while
     * leaving the caller to re-derive the new progress is an invitation to apply one without the other.
     */
    public record CommissionResult(CommissionSplit split, CapProgress progressAfter) {
        public CommissionResult {
            Objects.requireNonNull(split, "split must not be null");
            Objects.requireNonNull(progressAfter, "progressAfter must not be null");
        }

        /** True when this closing is the one that reached the cap. */
        public boolean reachedCap() {
            return split.reachedCapOnThisTransaction();
        }
    }
}
