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
    private AffiliationView affiliation = new AffiliationView();

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

    public AffiliationView getAffiliation() {
        return affiliation;
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
     * <p><strong>Two populations per tier, and the distinction is the point.</strong> Awards give the <em>earning</em>
     * downline; {@code AgentEnrolled} gives the roster. For a long time only the first existed, which meant an agent
     * who had been sponsored but had never closed anything produced no award and appeared nowhere — accurate for the
     * money and wrong as an org chart. Both are kept because neither answers the other's question, and the gap between
     * their counts is the figure that says whether recruiting turned into income.
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

        /** Records a sponsored agent at a tier, from an enrolment rather than from an award. */
        public void addDownlineMember(String tier, String agentId, LocalDate joinedOn) {
            byTier.computeIfAbsent(tier, TierView::new).enrolled(agentId, joinedOn);
        }

        /** Marks a downline member departed, at whichever tier they occupy. */
        public void markDownlineMemberDeparted(String tier, String agentId, LocalDate terminatedOn) {
            byTier.computeIfAbsent(tier, TierView::new).departed(agentId, terminatedOn);
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
         *
         * <p>Strictly a subset of {@link #downline}: everyone who has earned this agent something is by definition
         * sponsored beneath them, but not everyone sponsored beneath them has earned anything.
         */
        private Set<String> contributors = new LinkedHashSet<>();

        /**
         * Every agent sponsored at this depth, earning or not.
         *
         * <p>The org chart, kept beside the earnings rather than replacing them. Both numbers are worth having and
         * neither substitutes for the other: {@link #contributors} answers "who is paying me", {@code downline} answers
         * "how many people are under me", and the gap between them is the most interesting figure on the tier — an
         * agent with thirty in their downline and two contributors has a recruiting record and no income from it.
         *
         * <p>Keyed by agent id so a departure can be recorded against an existing member rather than appended as a
         * second entry. Membership is never removed; see {@link DownlineMember}.
         */
        private Map<String, DownlineMember> downline = new LinkedHashMap<>();

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

        public Map<String, DownlineMember> getDownline() {
            return downline;
        }

        public int getDownlineCount() {
            return downline.size();
        }

        /** Downline members who have not left. */
        public int getActiveDownlineCount() {
            return (int)
                    downline.values().stream().filter(DownlineMember::isActive).count();
        }

        void add(String contributorId, BigDecimal awarded, BigDecimal forfeited) {
            this.contributors.add(contributorId);
            this.awarded = this.awarded.add(awarded);
            this.forfeited = this.forfeited.add(forfeited);

            // An award is itself proof of membership: it names a contributor at this tier, so
            // they are in this agent's downline at this depth whether or not an enrolment
            // event ever said so. Registering them here keeps `contributors` a subset of
            // `downline` by construction, which matters for the agents who were enrolled
            // before the write side emitted AgentEnrolled at all - without it their ancestors
            // would report more contributors than downline and the dashboard would look
            // broken. The join date stays null, because the award does not carry one and
            // guessing would be worse than admitting it is unknown.
            downline.computeIfAbsent(contributorId, DownlineMember::new);
        }

        /** Records a sponsored agent at this depth, whether or not they have ever produced. */
        void enrolled(String agentId, LocalDate joinedOn) {
            downline.computeIfAbsent(agentId, DownlineMember::new).joined(joinedOn);
        }

        /**
         * Marks a member departed.
         *
         * <p>Marked, never removed — the hierarchy does not compress, so an agent who leaves stays at their depth
         * forever and everyone beneath them keeps their tier. Removing the entry would misstate the shape of the tree
         * and, worse, would make the downline count disagree with the awards still arriving through them.
         *
         * <p>Creates the entry if it is missing, because a termination can legitimately arrive for an agent this
         * dashboard never saw enrolled — the read side may have been deployed after they joined.
         */
        void departed(String agentId, LocalDate terminatedOn) {
            downline.computeIfAbsent(agentId, DownlineMember::new).left(terminatedOn);
        }
    }

    /**
     * One agent in someone's downline, and whether they are still here.
     *
     * <p>A document rather than a bare id because "is this person still with the brokerage" is the question that makes
     * a roster useful, and it cannot be answered from a set of ids. Both dates are nullable and their absence is
     * meaningful: a null {@code joinedOn} means membership was inferred from an award rather than from an enrolment,
     * and a null {@code terminatedOn} means the agent has not left.
     */
    public static class DownlineMember {
        private String agentId;
        private LocalDate joinedOn;
        private LocalDate terminatedOn;

        protected DownlineMember() {
            // Spring Data materialisation.
        }

        public DownlineMember(String agentId) {
            this.agentId = agentId;
        }

        public String getAgentId() {
            return agentId;
        }

        public LocalDate getJoinedOn() {
            return joinedOn;
        }

        public LocalDate getTerminatedOn() {
            return terminatedOn;
        }

        public boolean isActive() {
            return terminatedOn == null;
        }

        void joined(LocalDate on) {
            this.joinedOn = on;
        }

        void left(LocalDate on) {
            this.terminatedOn = on;
        }
    }

    /**
     * The agent's own standing with the brokerage.
     *
     * <p>Null until an {@code AgentEnrolled} names them. That is a real distinction and not a gap: a dashboard can
     * exist without it for an agent this service learned about only from a closing or an award, which is every agent
     * enrolled before the write side began announcing enrolments.
     */
    public static class AffiliationView {
        private LocalDate joinedOn;
        private String sponsorId;
        private LocalDate terminatedOn;

        public LocalDate getJoinedOn() {
            return joinedOn;
        }

        public String getSponsorId() {
            return sponsorId;
        }

        public LocalDate getTerminatedOn() {
            return terminatedOn;
        }

        public boolean isActive() {
            return terminatedOn == null;
        }

        public void enrolled(LocalDate joinedOn, String sponsorId) {
            this.joinedOn = joinedOn;
            this.sponsorId = sponsorId;
        }

        public void terminated(LocalDate on) {
            this.terminatedOn = on;
        }
    }
}
