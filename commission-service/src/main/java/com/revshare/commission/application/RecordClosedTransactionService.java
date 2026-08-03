package com.revshare.commission.application;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionCalculator;
import com.revshare.domain.commission.CommissionPlan;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.event.CapThresholdReached;
import com.revshare.domain.event.CommissionCalculated;
import com.revshare.domain.event.DomainEvent;
import com.revshare.domain.event.RevenueShareDistributed;
import com.revshare.domain.port.in.RecordClosedTransaction;
import com.revshare.domain.port.out.AgentRepository;
import com.revshare.domain.port.out.CapProgressRepository;
import com.revshare.domain.port.out.CommissionSplitRepository;
import com.revshare.domain.port.out.DomainEventPublisher;
import com.revshare.domain.port.out.RevenueShareLedger;
import com.revshare.domain.revshare.ProducingAgentPolicy;
import com.revshare.domain.revshare.RevenueShareCalculator;
import com.revshare.domain.revshare.RevenueShareDistribution;
import com.revshare.domain.revshare.RevenueSharePlan;
import com.revshare.domain.transaction.ClosedTransaction;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices a closing and distributes the revenue share it funds. The write side's one use case.
 *
 * <h2>The transaction boundary</h2>
 *
 * <p>Everything happens in a single database transaction: the cap advances, the split is recorded, the ledger is
 * appended, and the events are written to the outbox. That is not incidental. If the cap advanced but the split did not
 * persist, the agent would have paid toward their cap for a sale with no record; if the ledger were written but the
 * events were not, the read side would never learn that anyone was paid. The outbox is what lets the events join that
 * transaction instead of being a second, independent write to a broker.
 *
 * <p>This class does no arithmetic. Both calculators are pure functions living in the core, and the orchestration here
 * is deliberately dull: load, calculate, persist, announce. The interesting decisions are all one layer down, where
 * they can be tested without a database.
 */
@Service
public class RecordClosedTransactionService implements RecordClosedTransaction {

    private final AgentRepository agents;
    private final CapProgressRepository capProgress;
    private final CommissionSplitRepository splits;
    private final RevenueShareLedger ledger;
    private final DomainEventPublisher events;
    private final BeneficiaryStandingResolver standings;

    private final CommissionCalculator commissionCalculator;
    private final RevenueShareCalculator revenueShareCalculator;
    private final CommissionPlan commissionPlan;
    private final RevenueSharePlan revenueSharePlan;
    private final ProducingAgentPolicy producingPolicy;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public RecordClosedTransactionService(
            AgentRepository agents,
            CapProgressRepository capProgress,
            CommissionSplitRepository splits,
            RevenueShareLedger ledger,
            DomainEventPublisher events,
            BeneficiaryStandingResolver standings,
            CommissionCalculator commissionCalculator,
            RevenueShareCalculator revenueShareCalculator,
            CommissionPlan commissionPlan,
            RevenueSharePlan revenueSharePlan,
            ProducingAgentPolicy producingPolicy,
            Clock clock) {
        this.agents = agents;
        this.capProgress = capProgress;
        this.splits = splits;
        this.ledger = ledger;
        this.events = events;
        this.standings = standings;
        this.commissionCalculator = commissionCalculator;
        this.revenueShareCalculator = revenueShareCalculator;
        this.commissionPlan = commissionPlan;
        this.revenueSharePlan = revenueSharePlan;
        this.producingPolicy = producingPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Receipt record(ClosedTransaction transaction) {
        Agent agent = agents.findById(transaction.agentId())
                .orElseThrow(() -> new UnknownAgentException(transaction.agentId()));

        CapYear capYear = agent.capYearOn(transaction.closedOn());

        // Idempotency check number one, on the way in. It catches the common case cheaply -
        // a redelivered event, or a client retrying a timed-out call. It does not catch two
        // deliveries racing each other, which is what the primary key on commission_split
        // is for; see the catch below.
        if (splits.exists(transaction.id())) {
            return replayOf(transaction, agent, capYear);
        }

        CapProgress before = capProgress.findOrOpen(agent.id(), capYear);
        CommissionCalculator.CommissionResult priced =
                commissionCalculator.calculate(transaction, before, commissionPlan, agent.eliteStatus());

        try {
            splits.save(transaction, priced.split(), capYear);
        } catch (CommissionSplitRepository.DuplicateTransactionException e) {
            // Idempotency check number two. Another delivery won the race between our
            // exists() call and this insert. Their pricing is authoritative; ours is
            // discarded rather than applied on top, which would double-charge the cap.
            return replayOf(transaction, agent, capYear);
        }

        capProgress.save(priced.progressAfter());

        RevenueShareDistribution distribution = distribute(agent, priced.split(), capYear);
        ledger.record(distribution, capYear);

        events.publishAll(eventsFor(priced, distribution, capYear));

        return new Receipt(priced.split(), priced.progressAfter(), distribution, false);
    }

    /**
     * Distributes revenue share up the contributor's frozen sponsorship path.
     *
     * <p>Skips the standing lookups entirely when the closing funds nothing — a post-cap closing generates no company
     * dollar, so there is no point resolving five beneficiaries to award them zero each.
     */
    private RevenueShareDistribution distribute(Agent contributor, CommissionSplit split, CapYear capYear) {
        List<AgentId> upline = contributor.sponsorshipPath().revenueShareUpline();

        if (upline.isEmpty() || !split.generatesRevenueShare()) {
            return RevenueShareDistribution.none(
                    split.transactionId(), split.agentId(), split.closedOn(), split.revenueShareEligibleGross());
        }

        Map<AgentId, com.revshare.domain.revshare.BeneficiaryStanding> resolved =
                standings.resolve(upline, contributor.id(), split.closedOn(), capYear);

        return revenueShareCalculator.distribute(
                split, contributor.sponsorshipPath(), resolved, revenueSharePlan, producingPolicy);
    }

    /**
     * Reconstructs the outcome of a closing that was already priced.
     *
     * <p>Emits no events and writes nothing. The original recording already announced itself, and re-announcing would
     * have every downstream consumer process the same closing twice — which is the exact failure idempotency exists to
     * prevent.
     */
    private Receipt replayOf(ClosedTransaction transaction, Agent agent, CapYear capYear) {
        CommissionSplit existing = splits.findByTransactionId(transaction.id())
                .orElseThrow(() -> new IllegalStateException(
                        "transaction " + transaction.id() + " reported as recorded but could not be read back"));

        CapProgress current = capProgress
                .find(agent.id(), capYear)
                .orElseGet(() -> CapProgress.opening(agent.id(), capYear, commissionPlan));

        return new Receipt(
                existing,
                current,
                RevenueShareDistribution.none(
                        existing.transactionId(),
                        existing.agentId(),
                        existing.closedOn(),
                        existing.revenueShareEligibleGross()),
                true);
    }

    /**
     * The events this closing announces.
     *
     * <p>Note what is absent: {@code TransactionClosed}. That is this service's <em>input</em>, emitted by whatever
     * driving adapter learned about the sale. Re-emitting it here would feed the service its own output the moment a
     * Kafka consumer is wired to that topic.
     */
    private List<DomainEvent> eventsFor(
            CommissionCalculator.CommissionResult priced, RevenueShareDistribution distribution, CapYear capYear) {

        Instant now = clock.instant();
        List<DomainEvent> emitted = new ArrayList<>(3);

        emitted.add(new CommissionCalculated(UUID.randomUUID(), now, priced.split(), priced.progressAfter()));

        // A distinct event rather than a flag, because the subscribers are different: the
        // agent's fee schedule changes, their upline stops earning from them for the year,
        // and somebody wants to send a congratulatory notification.
        if (priced.reachedCap()) {
            emitted.add(new CapThresholdReached(
                    UUID.randomUUID(),
                    now,
                    priced.split().agentId(),
                    capYear,
                    priced.split().transactionId(),
                    priced.split().closedOn(),
                    priced.progressAfter().capAmount()));
        }

        if (!distribution.isEmpty()) {
            emitted.add(new RevenueShareDistributed(UUID.randomUUID(), now, distribution));
        }

        return emitted;
    }

    /** The closing names an agent this service has never heard of. */
    public static class UnknownAgentException extends RuntimeException {
        public UnknownAgentException(AgentId agentId) {
            super("no agent found with id " + agentId);
        }
    }
}
