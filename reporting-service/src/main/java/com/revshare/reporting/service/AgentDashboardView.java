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
        Affiliation affiliation,
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
     * <p><strong>{@code downline} and {@code contributors} are different populations and both are served.</strong>
     * {@code downline} is everyone sponsored at this depth; {@code contributors} is the subset who have actually earned
     * this agent something. The gap between the two counts is the most useful number on the tier — thirty in the
     * downline and two contributors describes a recruiting record with no income behind it, and neither figure alone
     * says that.
     *
     * <p>{@code requiredToUnlock} is plan data, read from {@code RevenueShareTier} exactly as {@code rate} is. It is
     * <em>not</em> paired with a live "producing frontline" count, and that omission is deliberate: producing means
     * $450 of gross in the trailing six months, which is a policy evaluated against a clock, and deciding it here would
     * put a business rule in the read side. {@code contributorCount} is emphatically not that number — it is lifetime
     * earning, so an agent who produced two years ago and stopped still counts. Whether a tier is actually unlocked is
     * the write side's answer, and it is already visible in the forfeit reason on any award.
     *
     * @param contributors the agents whose production has earned this agent something
     * @param downline every agent sponsored at this depth, earning or not, departed or not
     */
    public record Tier(
            String tier,
            int depth,
            String rate,
            BigDecimal awarded,
            BigDecimal forfeited,
            int contributorCount,
            List<String> contributors,
            int downlineCount,
            int activeDownlineCount,
            int requiredToUnlock,
            List<DownlineMember> downline) {}

    /**
     * One agent in the downline.
     *
     * <p>{@code joinedOn} is null when membership was learned from an award rather than from an enrolment — true for
     * every agent who joined before the write side began announcing enrolments. {@code terminatedOn} is null while the
     * agent is still affiliated, and a departed agent is <em>listed, not removed</em>: the hierarchy does not compress,
     * so they keep their depth and everyone beneath them keeps their tier.
     */
    public record DownlineMember(String agentId, LocalDate joinedOn, LocalDate terminatedOn, boolean active) {}

    /**
     * The agent's own standing with the brokerage.
     *
     * <p>Null for an agent this service learned about only from a closing or an award, which is every agent enrolled
     * before enrolments were announced. Present and empty is not the same as absent, so it is served as null rather
     * than as a zeroed record.
     */
    public record Affiliation(LocalDate joinedOn, String sponsorId, LocalDate terminatedOn, boolean active) {}

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

        var storedAffiliation = document.getAffiliation();

        return new AgentDashboardView(
                document.getAgentId(),
                storedAffiliation.getJoinedOn() == null
                        ? null
                        : new Affiliation(
                                storedAffiliation.getJoinedOn(),
                                storedAffiliation.getSponsorId(),
                                storedAffiliation.getTerminatedOn(),
                                storedAffiliation.isActive()),
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
                            found == null ? List.of() : List.copyOf(found.getContributors()),
                            found == null ? 0 : found.getDownlineCount(),
                            found == null ? 0 : found.getActiveDownlineCount(),
                            tier.producingFrontlineRequired(),
                            found == null ? List.of() : membersOf(found));
                })
                .toList();
    }

    /** Renders one tier's roster, nearest thing to a stable order the stored map can give: insertion order. */
    private static List<DownlineMember> membersOf(AgentDashboardDocument.TierView tier) {
        return tier.getDownline().values().stream()
                .map(member -> new DownlineMember(
                        member.getAgentId(), member.getJoinedOn(), member.getTerminatedOn(), member.isActive()))
                .toList();
    }
}
