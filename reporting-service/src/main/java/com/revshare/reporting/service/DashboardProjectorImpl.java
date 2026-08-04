package com.revshare.reporting.service;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.agent.SponsorshipPath;
import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.event.AgentEnrolled;
import com.revshare.domain.event.AgentTerminated;
import com.revshare.domain.event.CapThresholdReached;
import com.revshare.domain.event.CommissionCalculated;
import com.revshare.domain.event.DomainEvent;
import com.revshare.domain.event.RevenueShareDistributed;
import com.revshare.domain.event.TransactionClosed;
import com.revshare.domain.revshare.RevenueShareAward;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.reporting.adapter.out.mongo.AgentDashboardMongoRepository;
import com.revshare.reporting.adapter.out.mongo.ProcessedEventMongoRepository;
import com.revshare.reporting.adapter.out.mongo.document.AgentDashboardDocument;
import com.revshare.reporting.adapter.out.mongo.document.ProcessedEventDocument;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the agent dashboard from the event stream.
 *
 * <h2>The transaction boundary, and why Mongo needs a replica set</h2>
 *
 * <p>Marking an event processed and applying it are one transaction. That is not ceremony. Most of this projection is
 * additive, so the two orderings without a transaction both lose:
 *
 * <ul>
 *   <li>mark, then apply — a crash in between leaves the event marked and never projected, and nothing will ever retry
 *       it, so the dashboard is permanently short by one closing;
 *   <li>apply, then mark — a crash in between replays the event and counts it twice.
 * </ul>
 *
 * <p>A single {@code RevenueShareDistributed} makes it sharper still: it touches up to five beneficiary dashboards, so
 * a crash part-way through leaves some updated and some not, and the replay re-applies the ones already done. Only a
 * multi-document transaction closes that.
 *
 * <p>Which is why the read store is a replica set even as a single node — Mongo offers no multi-document transactions
 * on a standalone server. {@code docker-compose.yml} runs it with {@code --replSet}, and Testcontainers'
 * {@code MongoDBContainer} does the same by default. Running this service against a standalone {@code mongod} fails at
 * the first event, loudly, which is the right way for that to go wrong.
 *
 * <h2>What this does not do</h2>
 *
 * <p>No arithmetic beyond addition. Every figure on the dashboard was computed by the write side's pure calculators and
 * carried in the event; the read side sums and groups, and if it ever needs to decide something, the rule has been put
 * in the wrong module.
 */
@Service
public class DashboardProjectorImpl implements DashboardProjector {

    private static final Logger log = LoggerFactory.getLogger(DashboardProjectorImpl.class);

    private final AgentDashboardMongoRepository dashboards;
    private final ProcessedEventMongoRepository processedEvents;
    private final Clock clock;

    public DashboardProjectorImpl(
            AgentDashboardMongoRepository dashboards, ProcessedEventMongoRepository processedEvents, Clock clock) {
        this.dashboards = dashboards;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean apply(DomainEvent event) {
        String eventId = event.eventId().toString();

        // The dedup check and the projection are in one transaction, so this read cannot go
        // stale between here and the writes below. Concurrency is not what it defends
        // against - a consumer group processes one partition on one thread, so the same
        // event is never in flight twice - redelivery after a crash or a rebalance is.
        if (processedEvents.existsById(eventId)) {
            log.debug("skipping {} {}, already projected", event.getClass().getSimpleName(), eventId);
            return false;
        }

        Instant now = clock.instant();
        processedEvents.save(
                new ProcessedEventDocument(eventId, event.getClass().getSimpleName(), event.occurredAt(), now));

        // Exhaustive over the sealed hierarchy: adding a sixth event type to the core is a
        // compile error here rather than an event that silently projects to nothing.
        switch (event) {
            case CommissionCalculated e -> project(e, now);
            case CapThresholdReached e -> project(e, now);
            case RevenueShareDistributed e -> project(e, now);
            case AgentEnrolled e -> project(e, now);
            case AgentTerminated e -> project(e, now);
            case TransactionClosed e -> ignore(e);
        }

        return true;
    }

    /**
     * Cap progress and production, for the selling agent.
     *
     * <p>The cap balance is overwritten from the event and the production totals are added to. That asymmetry is not an
     * inconsistency: the event carries cap progress as a post-state and the closing as a fact, so one is the latest
     * answer and the other is a new contribution.
     */
    private void project(CommissionCalculated event, Instant now) {
        CommissionSplit split = event.split();
        CapProgress progress = event.progressAfter();
        CapYear capYear = progress.capYear();

        AgentDashboardDocument dashboard = loadOrCreate(split.agentId());

        dashboard
                .getCapProgress()
                .observe(
                        capYear.start(),
                        capYear.endExclusive(),
                        capYear.ordinal(),
                        progress.contributed().amount(),
                        progress.capAmount().amount());

        dashboard
                .getProduction()
                .addClosing(
                        split.grossCommissionIncome().amount(),
                        split.agentEarnings().amount(),
                        split.capContribution().amount(),
                        split.postCapFeeCharged().amount());

        dashboard.touch(now);
        dashboards.save(dashboard);
    }

    /** The agent capped. Recorded as a fact with a date rather than inferred from the balance. */
    private void project(CapThresholdReached event, Instant now) {
        AgentDashboardDocument dashboard = loadOrCreate(event.agentId());
        dashboard.getCapProgress().markCapped(event.reachedOn());
        dashboard.touch(now);
        dashboards.save(dashboard);
    }

    /**
     * Revenue share, for every beneficiary named in the distribution.
     *
     * <p>This is the fan-out the event's own Javadoc warns about. The event is partitioned by the <em>contributor</em>,
     * because the annual tier maxima are drawn down per contributing agent and those draws must stay ordered. So a
     * beneficiary's earnings arrive spread across every partition their downline happens to land on, and are only
     * assembled here, in the document. A normalised read model would have to aggregate that on every dashboard load;
     * this is the case the whole read side is built around.
     *
     * <p>Forfeited awards are projected too, not filtered out. An award forfeited because the beneficiary failed the
     * Producing Agent Policy still establishes that the contributor is in their downline at that tier, and the
     * forfeited total is the figure that tells an agent what the policy cost them.
     */
    private void project(RevenueShareDistributed event, Instant now) {
        for (RevenueShareAward award : event.distribution().awards()) {
            AgentDashboardDocument dashboard = loadOrCreate(award.beneficiary());

            dashboard
                    .getRevenueShare()
                    .addAward(
                            award.tier().name(),
                            award.contributor().toString(),
                            award.awarded().amount(),
                            award.forfeited().amount());

            dashboard.touch(now);
            dashboards.save(dashboard);
        }
    }

    /**
     * A new agent: their own dashboard, and a downline entry on every ancestor within reach.
     *
     * <p>This is the second fan-out in the projector, and the one that makes the org chart possible. The event carries
     * the whole frozen sponsorship path, so placing the agent is an indexed walk rather than a lookup: the ancestor at
     * index 0 gains a tier 1 member, index 1 a tier 2 member, and so on. No tree is traversed and nothing is derived —
     * the write side already decided the shape, and this copies it.
     *
     * <p>Stops at {@code REVENUE_SHARE_DEPTH}. Ancestors beyond the fifth are real and are recorded in the path, but
     * the dashboard is organised by the tiers of the revenue share programme and has no row to put them in. Writing
     * them anywhere would invent a sixth tier the programme does not have.
     *
     * <p>The agent's own dashboard is created here even though it is empty, which is a deliberate change in what a 404
     * from the read API means: an enrolled agent who has never closed anything now has a dashboard reading zero, and
     * that zero is now a fact rather than an assumption. Only an agent nobody has ever enrolled is absent.
     */
    private void project(AgentEnrolled event, Instant now) {
        AgentDashboardDocument own = loadOrCreate(event.agentId());
        own.getAffiliation()
                .enrolled(
                        event.joinedOn(),
                        event.sponsorId().map(AgentId::toString).orElse(null));
        own.touch(now);
        dashboards.save(own);

        forEachAncestorInReach(event.sponsorshipPath(), (ancestor, tier) -> {
            AgentDashboardDocument dashboard = loadOrCreate(ancestor);
            dashboard
                    .getRevenueShare()
                    .addDownlineMember(tier.name(), event.agentId().toString(), event.joinedOn());
            dashboard.touch(now);
            dashboards.save(dashboard);
        });
    }

    /**
     * An agent left: marked departed on their own dashboard and on every ancestor's roster.
     *
     * <p><strong>Marked, not removed.</strong> The hierarchy does not compress — an agent who leaves stays at their
     * depth forever, their downline keeps its tier beneath them, and their upline keeps earning through them. Deleting
     * the entry would assert a tree shape the write side does not have, and would make a roster disagree with the
     * awards still arriving through the departed agent.
     */
    private void project(AgentTerminated event, Instant now) {
        AgentDashboardDocument own = loadOrCreate(event.agentId());
        own.getAffiliation().terminated(event.terminatedOn());
        own.touch(now);
        dashboards.save(own);

        forEachAncestorInReach(event.sponsorshipPath(), (ancestor, tier) -> {
            AgentDashboardDocument dashboard = loadOrCreate(ancestor);
            dashboard
                    .getRevenueShare()
                    .markDownlineMemberDeparted(tier.name(), event.agentId().toString(), event.terminatedOn());
            dashboard.touch(now);
            dashboards.save(dashboard);
        });
    }

    /**
     * Visits each ancestor the revenue share programme reaches, with the tier they occupy.
     *
     * <p>Index 0 of the path is tier 1, which is the one place this module converts between the two conventions. Doing
     * it here, once, is why neither projection above contains an off-by-one waiting to happen.
     */
    private static void forEachAncestorInReach(SponsorshipPath path, BiConsumer<AgentId, RevenueShareTier> visit) {
        List<AgentId> ancestors = path.ancestorsNearestFirst();

        for (int index = 0; index < ancestors.size(); index++) {
            AgentId ancestor = ancestors.get(index);
            // Empty past the fifth tier, which is what ends the walk. Asking the enum rather
            // than comparing against REVENUE_SHARE_DEPTH keeps the programme's reach defined
            // in one place: a sixth tier would extend this loop without an edit here.
            RevenueShareTier.atDepth(index + 1).ifPresent(tier -> visit.accept(ancestor, tier));
        }
    }

    /**
     * Deliberately projects nothing.
     *
     * <p>{@code TransactionClosed} is the write side's input, and everything it carries reappears in the
     * {@code CommissionCalculated} that answers it — priced, and with the cap contribution the dashboard actually
     * needs. Projecting both would double the production totals. It is consumed rather than ignored at the topic level
     * so that the exhaustive switch above stays exhaustive, and so this reasoning has somewhere to live.
     */
    private void ignore(TransactionClosed event) {
        log.debug(
                "transaction {} closed; awaiting its priced commission event",
                event.transaction().id());
    }

    private AgentDashboardDocument loadOrCreate(AgentId agentId) {
        String id = agentId.toString();
        return dashboards.findById(id).orElseGet(() -> new AgentDashboardDocument(id));
    }
}
