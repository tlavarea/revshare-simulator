package com.revshare.reporting.adapter.out.mongo.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One agent's dashboard, assembled from the event stream and served whole.
 *
 * <p>This is the document the whole read side exists to produce, and the shape is the argument for using a document
 * store at all. Rendering it from the write model would mean a join across {@code commission_split},
 * {@code cap_progress} and {@code revenue_share_award}, plus a recursive CTE over the sponsorship paths to group the
 * downline into five tiers — per dashboard load, for a page that changes only when a closing settles. Projecting it
 * once on write and reading it by primary key is where a document store earns its place rather than being a cache with
 * extra steps.
 *
 * <p>A class rather than a record, and mutable, because it is an aggregate being accumulated over a stream rather than
 * a value. That is the same distinction the domain core draws; see the root {@code CLAUDE.md}.
 *
 * <h2>Two kinds of field, and why it matters</h2>
 *
 * <p>{@link CapProgressView} is <em>absolute</em>: {@code CommissionCalculated} carries the resulting cap progress, so
 * projecting it is a plain overwrite. Replaying the same event twice lands on the same answer.
 *
 * <p>{@link ProductionView} and {@link RevenueShareView} are <em>accumulated</em>: they add up closings and awards, so
 * replaying an event twice would double-count. Nothing in this class defends against that. The guard lives one layer
 * out, in the projector, which records each event id and applies the update in the same Mongo transaction — see
 * {@code DashboardProjector}.
 *
 * <h2>Money is Decimal128, never a double</h2>
 *
 * <p>Every amount is annotated {@code targetType = DECIMAL128}. Left to itself Spring Data would store a
 * {@link BigDecimal} as a string, which is exact but cannot be summed or compared by an aggregation pipeline; the one
 * thing it must never become is a BSON double, which would put binary floating point back into commission arithmetic at
 * the last possible moment.
 */
@Document(collection = "agent_dashboard")
public class AgentDashboardDocument {

    /** The agent's id. Used directly as {@code _id}, so a dashboard load is a primary key lookup. */
    @Id
    private String agentId;

    private CapProgressView capProgress = new CapProgressView();
    private ProductionView production = new ProductionView();
    private RevenueShareView revenueShare = new RevenueShareView();

    /** When this document was last touched by a projection. Diagnostic, not part of the contract. */
    private Instant lastProjectedAt;

    protected AgentDashboardDocument() {
        // Spring Data materialisation.
    }

    public AgentDashboardDocument(String agentId) {
        this.agentId = agentId;
    }

    public String getAgentId() {
        return agentId;
    }

    public CapProgressView getCapProgress() {
        return capProgress;
    }

    public ProductionView getProduction() {
        return production;
    }

    public RevenueShareView getRevenueShare() {
        return revenueShare;
    }

    public Instant getLastProjectedAt() {
        return lastProjectedAt;
    }

    public void touch(Instant at) {
        this.lastProjectedAt = at;
    }

    /**
     * Where the agent stands against their cap, in the cap year the last event concerned.
     *
     * <p>Deliberately holds only the current cap year rather than a history. A dashboard answers "how am I doing this
     * year"; the full history is reconstructable from the event log and does not belong in a document read on every
     * page load. The cap year is carried explicitly so a stale dashboard is recognisable as stale — an anniversary
     * rolling over is visible as a changed window, not as a balance that silently resets.
     */
    public static class CapProgressView {
        private LocalDate capYearStart;
        private LocalDate capYearEndExclusive;
        private int capYearOrdinal;

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal contributed = BigDecimal.ZERO;

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal capAmount = BigDecimal.ZERO;

        private boolean capped;
        private LocalDate cappedOn;

        public LocalDate getCapYearStart() {
            return capYearStart;
        }

        public LocalDate getCapYearEndExclusive() {
            return capYearEndExclusive;
        }

        public int getCapYearOrdinal() {
            return capYearOrdinal;
        }

        public BigDecimal getContributed() {
            return contributed;
        }

        public BigDecimal getCapAmount() {
            return capAmount;
        }

        public boolean isCapped() {
            return capped;
        }

        public LocalDate getCappedOn() {
            return cappedOn;
        }

        /** How much company dollar is still owed before capping. Derived, so it is never stored out of step. */
        public BigDecimal getRemaining() {
            BigDecimal remaining = capAmount.subtract(contributed);
            return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
        }

        /**
         * Overwrites the window and balance from a {@code CommissionCalculated}.
         *
         * <p>A straight overwrite rather than an accumulation, because the event carries the post-state. Ordering is
         * what makes that safe: commission events are partitioned by agent, so one agent's closings are consumed in the
         * order they were priced and the last write is genuinely the latest.
         */
        public void observe(
                LocalDate start, LocalDate endExclusive, int ordinal, BigDecimal contributed, BigDecimal capAmount) {
            // An anniversary rolled over. The balance in the incoming event is already the new
            // year's, but the capped flag is a fact about the old one and has to be cleared
            // here - otherwise an agent who capped last year shows as capped from the first
            // closing of the new year, when they are back to owing the full $12,000.
            if (capYearStart != null && !capYearStart.equals(start)) {
                openedNewCapYear();
            }
            this.capYearStart = start;
            this.capYearEndExclusive = endExclusive;
            this.capYearOrdinal = ordinal;
            this.contributed = contributed;
            this.capAmount = capAmount;
        }

        /**
         * Records the moment the cap was reached.
         *
         * <p>Set from {@code CapThresholdReached} rather than inferred from {@code contributed == capAmount}. The
         * derived form would be wrong in both directions: it would light up on a rounding coincidence, and it would go
         * dark the instant the next cap year opened with a fresh balance, erasing the fact that the agent ever capped.
         */
        public void markCapped(LocalDate on) {
            this.capped = true;
            this.cappedOn = on;
        }

        /** Clears the capped flag when a new cap year opens. */
        public void openedNewCapYear() {
            this.capped = false;
            this.cappedOn = null;
        }
    }

    /** What the agent has sold, accumulated across every closing the read side has seen. */
    public static class ProductionView {
        private long closings;

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal grossCommissionIncome = BigDecimal.ZERO;

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal agentEarnings = BigDecimal.ZERO;

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal companyDollarContributed = BigDecimal.ZERO;

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal postCapFeesPaid = BigDecimal.ZERO;

        public long getClosings() {
            return closings;
        }

        public BigDecimal getGrossCommissionIncome() {
            return grossCommissionIncome;
        }

        public BigDecimal getAgentEarnings() {
            return agentEarnings;
        }

        public BigDecimal getCompanyDollarContributed() {
            return companyDollarContributed;
        }

        public BigDecimal getPostCapFeesPaid() {
            return postCapFeesPaid;
        }

        public void addClosing(
                BigDecimal gross, BigDecimal agentEarnings, BigDecimal capContribution, BigDecimal postCapFee) {
            this.closings++;
            this.grossCommissionIncome = this.grossCommissionIncome.add(gross);
            this.agentEarnings = this.agentEarnings.add(agentEarnings);
            this.companyDollarContributed = this.companyDollarContributed.add(capContribution);
            this.postCapFeesPaid = this.postCapFeesPaid.add(postCapFee);
        }
    }

    /**
     * What the agent has earned from their downline, and who that downline is.
     *
     * <p>Earnings and hierarchy share one structure because they are learned from the same fact. A
     * {@code RevenueShareDistributed} award names a beneficiary, a contributor and a tier — which is simultaneously
     * "you earned this much" and "that agent sits this many levels below you". Splitting them into two projections
     * would mean reading the same events twice to build two views that can then disagree.
     *
     * <p><strong>Consequence worth stating:</strong> the downline here is the <em>earning</em> downline. An agent who
     * has been sponsored but has never closed anything produces no award and therefore appears nowhere. That is
     * accurate for the earnings figures and incomplete as an org chart; the roster projection that would fill the gap
     * needs an agent-lifecycle event the write side does not yet emit. Recorded in the README as a documented
     * assumption rather than left to be discovered.
     */
    public static class RevenueShareView {

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal totalAwarded = BigDecimal.ZERO;

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal totalForfeited = BigDecimal.ZERO;

        /** Keyed by {@code RevenueShareTier} name, so the document reads as tier 1 through tier 5. */
        private Map<String, TierView> byTier = new LinkedHashMap<>();

        public BigDecimal getTotalAwarded() {
            return totalAwarded;
        }

        public BigDecimal getTotalForfeited() {
            return totalForfeited;
        }

        public Map<String, TierView> getByTier() {
            return byTier;
        }

        public void addAward(String tier, String contributorId, BigDecimal awarded, BigDecimal forfeited) {
            this.totalAwarded = this.totalAwarded.add(awarded);
            this.totalForfeited = this.totalForfeited.add(forfeited);
            byTier.computeIfAbsent(tier, TierView::new).add(contributorId, awarded, forfeited);
        }
    }

    /** One tier's earnings and the agents at that depth. */
    public static class TierView {
        private String tier;

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal awarded = BigDecimal.ZERO;

        @Field(targetType = FieldType.DECIMAL128)
        private BigDecimal forfeited = BigDecimal.ZERO;

        /**
         * The distinct agents at this depth who have contributed.
         *
         * <p>A set, so the same contributor closing ten deals appears once. Kept as ids only — resolving them to names
         * would mean either duplicating the roster into every ancestor's document or a second query at read time, and
         * neither is worth it before there is a UI asking for it.
         */
        private Set<String> contributors = new LinkedHashSet<>();

        protected TierView() {
            // Spring Data materialisation.
        }

        public TierView(String tier) {
            this.tier = tier;
        }

        public String getTier() {
            return tier;
        }

        public BigDecimal getAwarded() {
            return awarded;
        }

        public BigDecimal getForfeited() {
            return forfeited;
        }

        public Set<String> getContributors() {
            return contributors;
        }

        public int getContributorCount() {
            return contributors.size();
        }

        void add(String contributorId, BigDecimal awarded, BigDecimal forfeited) {
            this.contributors.add(contributorId);
            this.awarded = this.awarded.add(awarded);
            this.forfeited = this.forfeited.add(forfeited);
        }
    }
}
