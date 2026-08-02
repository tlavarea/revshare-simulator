package com.revshare.domain.revshare;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.TransactionId;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Every revenue share award arising from a single closing, paid and forfeited alike.
 *
 * <h2>A note on rounding</h2>
 *
 * <p>Each tier's entitlement is rounded to the cent independently, because each is a separate payable to a separate
 * person and none of them can be paid in fractions of a cent. The consequence is that the five entitlements can sum to
 * marginally more than the company dollar the closing generated: at most half a cent per tier, so no more than 2.5
 * cents on any one transaction, and only when several tiers round up together.
 *
 * <p>This is accepted rather than corrected. The alternative, allocating by largest remainder so the parts sum exactly
 * to the whole, would make one agent's payment depend on the rounding of four other agents' payments, which is far
 * harder to explain on a statement than a fraction of a cent is to absorb. The bound is asserted in the test suite so
 * the variance stays known rather than merely unnoticed.
 */
public record RevenueShareDistribution(
        TransactionId transactionId,
        AgentId contributor,
        LocalDate closedOn,
        Money eligibleGross,
        List<RevenueShareAward> awards) {

    public RevenueShareDistribution {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(contributor, "contributor must not be null");
        Objects.requireNonNull(closedOn, "closedOn must not be null");
        Objects.requireNonNull(eligibleGross, "eligibleGross must not be null");
        Objects.requireNonNull(awards, "awards must not be null");

        awards = List.copyOf(awards);

        long distinctBeneficiaries =
                awards.stream().map(RevenueShareAward::beneficiary).distinct().count();
        if (distinctBeneficiaries != awards.size()) {
            throw new IllegalArgumentException(
                    "a beneficiary may appear at most once per distribution; an agent occupies "
                            + "exactly one tier above any given contributor");
        }
    }

    /** No upline, or a closing that generated no company dollar to share. */
    public static RevenueShareDistribution none(
            TransactionId transactionId, AgentId contributor, LocalDate closedOn, Money eligibleGross) {
        return new RevenueShareDistribution(transactionId, contributor, closedOn, eligibleGross, List.of());
    }

    /** What the upline was owed before eligibility was applied. */
    public Money totalEntitlement() {
        return sum(RevenueShareAward::entitlement);
    }

    /** What the upline is actually paid. */
    public Money totalAwarded() {
        return sum(RevenueShareAward::awarded);
    }

    /** What the upline was owed but did not collect, which stays with the company. */
    public Money totalForfeited() {
        return sum(RevenueShareAward::forfeited);
    }

    /** Only the awards that resulted in a payment, nearest tier first. */
    public List<RevenueShareAward> paidAwards() {
        return awards.stream()
                .filter(RevenueShareAward::wasPaid)
                .sorted(Comparator.comparingInt(a -> a.tier().depth()))
                .toList();
    }

    public boolean isEmpty() {
        return awards.isEmpty();
    }

    private Money sum(java.util.function.Function<RevenueShareAward, Money> field) {
        Money total = Money.ZERO;
        for (RevenueShareAward award : awards) {
            total = total.plus(field.apply(award));
        }
        return total;
    }

    @Override
    public String toString() {
        return "RevenueShareDistribution[" + transactionId + " from " + contributor
                + ": awarded " + totalAwarded() + ", forfeited " + totalForfeited()
                + " across " + awards.size() + " beneficiaries]";
    }
}
