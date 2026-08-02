package com.revshare.domain.commission;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.TransactionId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The outcome of applying a {@link CommissionPlan} to one closed transaction.
 *
 * <p>Records not just the two payout figures but the reasoning behind them: how much went to the cap, whether a flat
 * fee was charged instead of a split, and whether this was the transaction that tipped the agent over. A dashboard that
 * can only show "you earned X" cannot answer the question agents actually ask, which is "why is this one different from
 * the last one?"
 *
 * <p>{@code revenueShareEligibleGross} is the field that connects this half of the system to the other. Revenue share
 * is assessed on gross commission, but only on the portion that actually produced company dollar. A transaction closed
 * after capping generates none, and a transaction that straddles the cap generates it only up to the crossing point.
 */
public record CommissionSplit(
        TransactionId transactionId,
        AgentId agentId,
        LocalDate closedOn,
        Money grossCommissionIncome,
        Money agentEarnings,
        Money companyEarnings,
        Money capContribution,
        Money postCapFeeCharged,
        Money revenueShareEligibleGross,
        boolean pricedUnderPostCapFee,
        boolean reachedCapOnThisTransaction) {

    public CommissionSplit {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(closedOn, "closedOn must not be null");
        Objects.requireNonNull(grossCommissionIncome, "grossCommissionIncome must not be null");
        Objects.requireNonNull(agentEarnings, "agentEarnings must not be null");
        Objects.requireNonNull(companyEarnings, "companyEarnings must not be null");
        Objects.requireNonNull(capContribution, "capContribution must not be null");
        Objects.requireNonNull(postCapFeeCharged, "postCapFeeCharged must not be null");
        Objects.requireNonNull(revenueShareEligibleGross, "revenueShareEligibleGross must not be null");

        // Conservation of money: nothing is created or destroyed by a split.
        Money distributed = agentEarnings.plus(companyEarnings);
        if (!distributed.equals(grossCommissionIncome)) {
            throw new IllegalArgumentException("split does not balance: agent " + agentEarnings + " + company "
                    + companyEarnings + " = " + distributed
                    + ", but gross commission was " + grossCommissionIncome);
        }
        // The company is paid either by the split or by the flat fee, never both.
        Money companyComponents = capContribution.plus(postCapFeeCharged);
        if (!companyComponents.equals(companyEarnings)) {
            throw new IllegalArgumentException("company earnings " + companyEarnings + " do not decompose into cap "
                    + capContribution + " + fee " + postCapFeeCharged);
        }
        if (revenueShareEligibleGross.isGreaterThan(grossCommissionIncome)) {
            throw new IllegalArgumentException("revenue-share-eligible gross " + revenueShareEligibleGross
                    + " exceeds gross commission " + grossCommissionIncome);
        }
    }

    /** True when this closing produced no company dollar and so funds no revenue share. */
    public boolean generatesRevenueShare() {
        return revenueShareEligibleGross.isPositive();
    }

    @Override
    public String toString() {
        return "CommissionSplit[" + transactionId + " gross " + grossCommissionIncome
                + " -> agent " + agentEarnings + ", company " + companyEarnings
                + (pricedUnderPostCapFee ? " (flat fee)" : " (split)")
                + (reachedCapOnThisTransaction ? " CAP REACHED" : "") + "]";
    }
}
