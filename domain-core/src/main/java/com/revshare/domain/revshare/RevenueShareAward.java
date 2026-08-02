package com.revshare.domain.revshare;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.TransactionId;
import java.util.Objects;

/**
 * What one beneficiary earned, or failed to earn, from one contributor's closing.
 *
 * <p>Emitted for every ancestor within reach of the program, including those who earned nothing. A zero-value award
 * carrying a {@link ForfeitReason} is the record that lets a dashboard explain an absence, and it costs one row to
 * keep.
 *
 * <p>{@code entitlement} is what the tier rate produced before any eligibility test; {@code awarded} is what was
 * actually paid; {@code forfeited} is the difference. The three always reconcile, which is asserted here rather than
 * trusted.
 */
public record RevenueShareAward(
        AgentId beneficiary,
        AgentId contributor,
        TransactionId transactionId,
        RevenueShareTier tier,
        Money eligibleGross,
        Money entitlement,
        Money awarded,
        Money forfeited,
        ForfeitReason forfeitReason) {

    public RevenueShareAward {
        Objects.requireNonNull(beneficiary, "beneficiary must not be null");
        Objects.requireNonNull(contributor, "contributor must not be null");
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(eligibleGross, "eligibleGross must not be null");
        Objects.requireNonNull(entitlement, "entitlement must not be null");
        Objects.requireNonNull(awarded, "awarded must not be null");
        Objects.requireNonNull(forfeited, "forfeited must not be null");
        Objects.requireNonNull(forfeitReason, "forfeitReason must not be null");

        if (beneficiary.equals(contributor)) {
            throw new IllegalArgumentException(
                    "an agent cannot earn revenue share from their own production: " + beneficiary);
        }
        if (awarded.isNegative() || forfeited.isNegative()) {
            throw new IllegalArgumentException("awarded and forfeited amounts must not be negative");
        }
        if (!awarded.plus(forfeited).equals(entitlement)) {
            throw new IllegalArgumentException("award does not reconcile: awarded " + awarded + " + forfeited "
                    + forfeited + " != entitlement " + entitlement);
        }
        if (forfeited.isPositive() != forfeitReason.isForfeiture()) {
            throw new IllegalArgumentException(
                    "forfeited amount " + forfeited + " is inconsistent with reason " + forfeitReason);
        }
    }

    /** The whole entitlement was paid. */
    public static RevenueShareAward paid(
            AgentId beneficiary,
            AgentId contributor,
            TransactionId transactionId,
            RevenueShareTier tier,
            Money eligibleGross,
            Money entitlement) {
        return new RevenueShareAward(
                beneficiary,
                contributor,
                transactionId,
                tier,
                eligibleGross,
                entitlement,
                entitlement,
                Money.ZERO,
                ForfeitReason.NONE);
    }

    /** Nothing was paid, for a reason that disqualifies the beneficiary entirely. */
    public static RevenueShareAward forfeited(
            AgentId beneficiary,
            AgentId contributor,
            TransactionId transactionId,
            RevenueShareTier tier,
            Money eligibleGross,
            Money entitlement,
            ForfeitReason reason) {
        if (!reason.isForfeiture()) {
            throw new IllegalArgumentException("a forfeited award needs a forfeit reason");
        }
        if (entitlement.isZero()) {
            // Nothing was due in the first place, so nothing was forfeited either.
            return new RevenueShareAward(
                    beneficiary,
                    contributor,
                    transactionId,
                    tier,
                    eligibleGross,
                    Money.ZERO,
                    Money.ZERO,
                    Money.ZERO,
                    ForfeitReason.NONE);
        }
        return new RevenueShareAward(
                beneficiary,
                contributor,
                transactionId,
                tier,
                eligibleGross,
                entitlement,
                Money.ZERO,
                entitlement,
                reason);
    }

    /** Paid up to a remaining annual allowance, with the excess forfeited. */
    public static RevenueShareAward cappedAtAnnualMaximum(
            AgentId beneficiary,
            AgentId contributor,
            TransactionId transactionId,
            RevenueShareTier tier,
            Money eligibleGross,
            Money entitlement,
            Money remainingAllowance) {
        Money payable = Money.min(entitlement, remainingAllowance.atLeastZero());
        Money lost = entitlement.minus(payable);
        return new RevenueShareAward(
                beneficiary,
                contributor,
                transactionId,
                tier,
                eligibleGross,
                entitlement,
                payable,
                lost,
                lost.isPositive() ? ForfeitReason.ANNUAL_TIER_MAXIMUM_REACHED : ForfeitReason.NONE);
    }

    public boolean wasPaid() {
        return awarded.isPositive();
    }

    @Override
    public String toString() {
        return "RevenueShareAward[" + beneficiary + " <- " + contributor + " " + tier
                + " awarded " + awarded
                + (forfeitReason.isForfeiture() ? ", forfeited " + forfeited + " (" + forfeitReason + ")" : "")
                + "]";
    }
}
