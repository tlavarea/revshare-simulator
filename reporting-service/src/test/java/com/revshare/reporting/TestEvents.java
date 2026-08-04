package com.revshare.reporting;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.event.CapThresholdReached;
import com.revshare.domain.event.CommissionCalculated;
import com.revshare.domain.event.RevenueShareDistributed;
import com.revshare.domain.revshare.ForfeitReason;
import com.revshare.domain.revshare.RevenueShareAward;
import com.revshare.domain.revshare.RevenueShareDistribution;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.domain.shared.Money;
import com.revshare.domain.shared.Percentage;
import com.revshare.domain.transaction.TransactionId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Events shaped the way the write side emits them.
 *
 * <p>Built through the domain records' real constructors, so every fixture here has already satisfied the same
 * invariants a genuine event does — a split that does not balance cannot be written into a test by accident. That is
 * worth more than the brevity a hand-rolled builder would buy, and it is the reason this module depends on
 * {@code domain-core} rather than on local DTOs.
 */
public final class TestEvents {

    /** A cap year starting on an arbitrary anniversary, chosen not to be 1 January. */
    public static final LocalDate ANNIVERSARY = LocalDate.of(2025, 3, 14);

    public static final CapYear CAP_YEAR = new CapYear(ANNIVERSARY, ANNIVERSARY.plusYears(1), 0);
    public static final CapYear NEXT_CAP_YEAR = new CapYear(ANNIVERSARY.plusYears(1), ANNIVERSARY.plusYears(2), 1);

    public static final Money ANNUAL_CAP = Money.of("12000.00");

    private TestEvents() {}

    public static AgentId agent() {
        return AgentId.newId();
    }

    public static TransactionId transaction() {
        return TransactionId.newId();
    }

    /**
     * A pre-cap closing: 85/15, nothing capped, the whole gross funds revenue share.
     *
     * @param gross gross commission income, split 85/15
     * @param contributedAfter the agent's cumulative cap contribution once this closing is applied
     */
    public static CommissionCalculated closing(
            AgentId agentId, TransactionId transactionId, LocalDate closedOn, String gross, String contributedAfter) {

        Money grossCommission = Money.of(gross);
        Money company = grossCommission.multipliedBy(Percentage.ofPercent("15"));
        Money agent = grossCommission.minus(company);

        CommissionSplit split = new CommissionSplit(
                transactionId,
                agentId,
                closedOn,
                grossCommission,
                agent,
                company,
                company,
                Money.ZERO,
                grossCommission,
                false,
                false);

        CapProgress progress = new CapProgress(agentId, capYearOf(closedOn), Money.of(contributedAfter), ANNUAL_CAP);

        return new CommissionCalculated(UUID.randomUUID(), Instant.now(), split, progress);
    }

    /**
     * A post-cap closing: the agent keeps everything but the flat transaction fee, and nothing funds revenue share.
     *
     * <p>Included because it is the case where production and cap progress move differently — the dashboard's closing
     * count and gross rise while the cap balance stands still.
     */
    public static CommissionCalculated postCapClosing(
            AgentId agentId, TransactionId transactionId, LocalDate closedOn, String gross, String flatFee) {

        Money grossCommission = Money.of(gross);
        Money fee = Money.of(flatFee);
        Money agent = grossCommission.minus(fee);

        CommissionSplit split = new CommissionSplit(
                transactionId,
                agentId,
                closedOn,
                grossCommission,
                agent,
                fee,
                Money.ZERO,
                fee,
                Money.ZERO,
                true,
                false);

        CapProgress progress = new CapProgress(agentId, capYearOf(closedOn), ANNUAL_CAP, ANNUAL_CAP);

        return new CommissionCalculated(UUID.randomUUID(), Instant.now(), split, progress);
    }

    public static CapThresholdReached capped(AgentId agentId, TransactionId transactionId, LocalDate reachedOn) {
        return new CapThresholdReached(
                UUID.randomUUID(), Instant.now(), agentId, capYearOf(reachedOn), transactionId, reachedOn, ANNUAL_CAP);
    }

    /** One award, fully paid, at the given tier. */
    public static RevenueShareDistributed award(
            AgentId beneficiary,
            AgentId contributor,
            TransactionId transactionId,
            RevenueShareTier tier,
            String eligibleGross,
            String amount) {

        RevenueShareAward paid = RevenueShareAward.paid(
                beneficiary, contributor, transactionId, tier, Money.of(eligibleGross), Money.of(amount));

        return distribution(contributor, transactionId, eligibleGross, List.of(paid));
    }

    /** One award, entirely forfeited — the beneficiary is in the downline but collects nothing. */
    public static RevenueShareDistributed forfeited(
            AgentId beneficiary,
            AgentId contributor,
            TransactionId transactionId,
            RevenueShareTier tier,
            String eligibleGross,
            String amount,
            ForfeitReason reason) {

        RevenueShareAward award = new RevenueShareAward(
                beneficiary,
                contributor,
                transactionId,
                tier,
                Money.of(eligibleGross),
                Money.of(amount),
                Money.ZERO,
                Money.of(amount),
                reason);

        return distribution(contributor, transactionId, eligibleGross, List.of(award));
    }

    /** A whole upline paid at once, nearest ancestor first. */
    public static RevenueShareDistributed upline(
            List<AgentId> beneficiaries, AgentId contributor, TransactionId transactionId, String eligibleGross) {

        RevenueShareTier[] tiers = RevenueShareTier.values();
        List<RevenueShareAward> awards = new java.util.ArrayList<>();

        for (int depth = 0; depth < beneficiaries.size(); depth++) {
            RevenueShareTier tier = tiers[depth];
            Money entitlement = Money.of(eligibleGross).multipliedBy(tier.rate());
            awards.add(RevenueShareAward.paid(
                    beneficiaries.get(depth), contributor, transactionId, tier, Money.of(eligibleGross), entitlement));
        }

        return distribution(contributor, transactionId, eligibleGross, awards);
    }

    private static RevenueShareDistributed distribution(
            AgentId contributor, TransactionId transactionId, String eligibleGross, List<RevenueShareAward> awards) {

        RevenueShareDistribution distribution = new RevenueShareDistribution(
                transactionId, contributor, ANNIVERSARY.plusMonths(2), Money.of(eligibleGross), awards);

        return new RevenueShareDistributed(UUID.randomUUID(), Instant.now(), distribution);
    }

    private static CapYear capYearOf(LocalDate on) {
        return CapYear.containing(ANNIVERSARY, on);
    }
}
