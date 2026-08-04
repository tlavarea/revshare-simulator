package com.revshare.reporting.service;

import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.reporting.adapter.out.mongo.document.AgentDashboardDocument;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One agent's dashboard as it is served.
 *
 * <p>Records, not the Mongo document. The document is a storage shape — mutable, accumulating, with getters shaped for
 * a projector — and publishing it directly would make every field name and nesting decision in the read model part of
 * the API, so a change to how the projection is stored would be a breaking change to clients. This is the same
 * separation the write side keeps between its entities and the domain: {@code PersistenceMapper} there,
 * {@link #from(AgentDashboardDocument)} here.
 *
 * <p>Deliberately <em>not</em> a third shape. The controller serialises this as-is rather than mapping it into web
 * DTOs; for a read side whose entire purpose is serving this one projection, a service-layer read model and an API
 * response are the same thing, and splitting them would be ceremony with no seam behind it.
 */
public record AgentDashboardView(
        String agentId,
        CapProgress capProgress,
        Production production,
        RevenueShare revenueShare,
        Instant lastProjectedAt) {

    /**
     * The anniversary window the cap accrues over. Included so a client can tell a stale dashboard from a reset one.
     */
    public record CapYear(LocalDate start, LocalDate endExclusive, int ordinal) {}

    public record CapProgress(
            CapYear capYear,
            BigDecimal contributed,
            BigDecimal capAmount,
            BigDecimal remaining,
            boolean capped,
            LocalDate cappedOn) {}

    public record Production(
            long closings,
            BigDecimal grossCommissionIncome,
            BigDecimal agentEarnings,
            BigDecimal companyDollarContributed,
            BigDecimal postCapFeesPaid) {}

    public record RevenueShare(BigDecimal totalAwarded, BigDecimal totalForfeited, List<Tier> tiers) {}

    /**
     * One tier's earnings and the agents at that depth.
     *
     * @param contributors the agents whose production earned this, which is the downline at this depth
     */
    public record Tier(
            String tier,
            int depth,
            String rate,
            BigDecimal awarded,
            BigDecimal forfeited,
            int contributorCount,
            List<String> contributors) {}

    /**
     * Renders a stored dashboard.
     *
     * <p><strong>All five tiers are always present</strong>, including the ones with nothing in them. A client
     * rendering a five-tier programme should not have to know that five is the number, nor treat an absent key and a
     * zero differently — "tier 3: $0" and "tier 3 missing" mean the same thing to an agent and should not require
     * different code to display.
     */
    public static AgentDashboardView from(AgentDashboardDocument document) {
        var storedCap = document.getCapProgress();
        var storedProduction = document.getProduction();
        var storedRevenueShare = document.getRevenueShare();

        // Null until the first commission event lands. A dashboard can exist without one: an
        // agent who has earned revenue share but not yet closed anything themselves.
        CapYear capYear = storedCap.getCapYearStart() == null
                ? null
                : new CapYear(
                        storedCap.getCapYearStart(), storedCap.getCapYearEndExclusive(), storedCap.getCapYearOrdinal());

        return new AgentDashboardView(
                document.getAgentId(),
                new CapProgress(
                        capYear,
                        storedCap.getContributed(),
                        storedCap.getCapAmount(),
                        storedCap.getRemaining(),
                        storedCap.isCapped(),
                        storedCap.getCappedOn()),
                new Production(
                        storedProduction.getClosings(),
                        storedProduction.getGrossCommissionIncome(),
                        storedProduction.getAgentEarnings(),
                        storedProduction.getCompanyDollarContributed(),
                        storedProduction.getPostCapFeesPaid()),
                new RevenueShare(
                        storedRevenueShare.getTotalAwarded(),
                        storedRevenueShare.getTotalForfeited(),
                        allTiers(storedRevenueShare.getByTier())),
                document.getLastProjectedAt());
    }

    private static List<Tier> allTiers(Map<String, AgentDashboardDocument.TierView> stored) {
        return java.util.Arrays.stream(RevenueShareTier.values())
                .map(tier -> {
                    var found = stored.get(tier.name());
                    return new Tier(
                            tier.name(),
                            tier.depth(),
                            tier.rate().toString(),
                            found == null ? BigDecimal.ZERO : found.getAwarded(),
                            found == null ? BigDecimal.ZERO : found.getForfeited(),
                            found == null ? 0 : found.getContributorCount(),
                            found == null ? List.of() : List.copyOf(found.getContributors()));
                })
                .toList();
    }
}
