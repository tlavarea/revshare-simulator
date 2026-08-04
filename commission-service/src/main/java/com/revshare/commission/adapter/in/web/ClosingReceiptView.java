package com.revshare.commission.adapter.in.web;

import com.revshare.domain.port.in.RecordClosedTransaction;
import com.revshare.domain.revshare.RevenueShareAward;
import com.revshare.domain.revshare.RevenueShareDistribution;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the closing produced, as it is served.
 *
 * <p>Records of {@link BigDecimal} and {@link String}, not the domain's {@code Receipt}. Serialising the port's return
 * type directly would publish {@code Money}, {@code AgentId} and {@code TransactionId} in whatever shape Jackson makes
 * of them, which is how the core's internal structure leaks into a wire contract — the same separation
 * {@code PersistenceMapper} keeps on the way to the database, and {@code AgentDashboardView} keeps on the read side.
 *
 * <p>The response is deliberately complete rather than an acknowledgement. A caller recording a closing wants to know
 * what the agent was actually paid and why it differs from last time, and every figure needed to answer that is already
 * in hand at the end of the transaction. Making them fetch it from the read side afterwards would mean waiting for a
 * projection that is only eventually consistent, to learn something the write side had already computed.
 */
public record ClosingReceiptView(
        String transactionId,
        String agentId,
        LocalDate closedOn,
        boolean alreadyRecorded,
        Split split,
        CapProgress capProgress,
        RevenueShare revenueShare) {

    /**
     * The pricing, decomposed.
     *
     * <p>{@code pricedUnderPostCapFee} and {@code reachedCap} are carried rather than left to be inferred from the
     * amounts, because they are the answer to the question an agent actually asks — "why is this one different from the
     * last one?" — and reconstructing them client-side would mean reimplementing the cap rules there.
     */
    public record Split(
            BigDecimal grossCommissionIncome,
            BigDecimal agentEarnings,
            BigDecimal companyEarnings,
            BigDecimal capContribution,
            BigDecimal postCapFeeCharged,
            BigDecimal revenueShareEligibleGross,
            boolean pricedUnderPostCapFee,
            boolean reachedCap) {}

    /** The anniversary window the cap accrues over, so a client can tell a stale figure from a reset one. */
    public record CapYear(LocalDate start, LocalDate endExclusive, int ordinal) {}

    public record CapProgress(
            CapYear capYear, BigDecimal contributed, BigDecimal capAmount, BigDecimal remaining, boolean capped) {}

    /**
     * Every award the closing funded, paid and forfeited alike.
     *
     * <p>Forfeited awards are included with their reason. A dashboard that only ever saw payments could not explain an
     * absence, and "your sponsor earned nothing from this sale" is a different statement from "your sponsor is not in
     * the response".
     */
    public record RevenueShare(
            BigDecimal eligibleGross,
            BigDecimal totalEntitlement,
            BigDecimal totalAwarded,
            BigDecimal totalForfeited,
            List<Award> awards) {}

    public record Award(
            String beneficiary,
            String tier,
            int depth,
            String rate,
            BigDecimal entitlement,
            BigDecimal awarded,
            BigDecimal forfeited,
            String forfeitReason) {}

    /**
     * Renders a receipt.
     *
     * <p><strong>{@code revenueShare} is null on a replay, and that is the honest answer rather than a gap.</strong>
     * {@code RecordClosedTransactionService.replayOf} reconstructs the split and the cap from storage but returns
     * {@code RevenueShareDistribution.none(...)} for the distribution — it does not re-read the ledger, because the
     * point of the replay path is to write nothing and announce nothing. Rendering that placeholder as
     * {@code totalAwarded: 0} would state as fact that this closing paid nobody, when what is true is that the original
     * recording paid an upline and this response does not know who. Omitting the block says exactly that. It is the
     * same choice the read side makes in returning 404 rather than an empty dashboard: an absence of knowledge must not
     * be served as a zero.
     */
    public static ClosingReceiptView from(RecordClosedTransaction.Receipt receipt) {
        var split = receipt.split();
        var progress = receipt.progressAfter();

        return new ClosingReceiptView(
                split.transactionId().toString(),
                split.agentId().toString(),
                split.closedOn(),
                receipt.alreadyRecorded(),
                new Split(
                        split.grossCommissionIncome().amount(),
                        split.agentEarnings().amount(),
                        split.companyEarnings().amount(),
                        split.capContribution().amount(),
                        split.postCapFeeCharged().amount(),
                        split.revenueShareEligibleGross().amount(),
                        split.pricedUnderPostCapFee(),
                        receipt.reachedCap()),
                new CapProgress(
                        new CapYear(
                                progress.capYear().start(),
                                progress.capYear().endExclusive(),
                                progress.capYear().ordinal()),
                        progress.contributed().amount(),
                        progress.capAmount().amount(),
                        progress.remaining().amount(),
                        progress.isCapped()),
                receipt.alreadyRecorded() ? null : revenueShareOf(receipt.distribution()));
    }

    private static RevenueShare revenueShareOf(RevenueShareDistribution distribution) {
        return new RevenueShare(
                distribution.eligibleGross().amount(),
                distribution.totalEntitlement().amount(),
                distribution.totalAwarded().amount(),
                distribution.totalForfeited().amount(),
                distribution.awards().stream().map(ClosingReceiptView::awardOf).toList());
    }

    private static Award awardOf(RevenueShareAward award) {
        return new Award(
                award.beneficiary().toString(),
                award.tier().name(),
                award.tier().depth(),
                award.tier().rate().toString(),
                award.entitlement().amount(),
                award.awarded().amount(),
                award.forfeited().amount(),
                award.forfeitReason().name());
    }
}
