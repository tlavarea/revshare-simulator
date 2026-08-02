package com.revshare.domain.transaction;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A closing that has already settled, and the gross commission it produced for one agent.
 *
 * <p>Immutable by design. This is the input to the entire calculation chain, and the business event it represents ("a
 * sale closed") is a fact about the past. Corrections are modeled as new compensating transactions rather than edits,
 * so a commission statement can always be recomputed from the event history and will produce the same numbers it did
 * the first time.
 *
 * <p>{@code grossCommissionIncome} is the agent's side of the commission before any split, not the total commission on
 * the sale. For a {@link TransactionSide#DUAL} closing it is both sides, because both accrue to this one agent.
 */
public record ClosedTransaction(
        TransactionId id,
        AgentId agentId,
        LocalDate closedOn,
        Money salePrice,
        Money grossCommissionIncome,
        TransactionSide side,
        String propertyReference) {

    public ClosedTransaction {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(closedOn, "closedOn must not be null");
        Objects.requireNonNull(salePrice, "salePrice must not be null");
        Objects.requireNonNull(grossCommissionIncome, "grossCommissionIncome must not be null");
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(propertyReference, "propertyReference must not be null");

        if (!salePrice.isPositive()) {
            throw new IllegalArgumentException("sale price must be positive, was " + salePrice);
        }
        if (!grossCommissionIncome.isPositive()) {
            throw new IllegalArgumentException(
                    "gross commission income must be positive, was " + grossCommissionIncome);
        }
        if (grossCommissionIncome.isGreaterThan(salePrice)) {
            throw new IllegalArgumentException(
                    "gross commission " + grossCommissionIncome + " exceeds sale price " + salePrice);
        }
    }

    /**
     * The effective commission rate this closing earned, as a fraction of sale price.
     *
     * <p>Reporting-only. Nothing in the split calculation derives from it, because the gross commission is an input
     * negotiated per deal rather than something recoverable from a standard rate.
     */
    public Money commissionPerSide() {
        return Money.of(grossCommissionIncome
                .amount()
                .divide(java.math.BigDecimal.valueOf(side.commissionSides()), Money.SCALE, Money.ROUNDING));
    }
}
