package com.revshare.commission.service;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.event.AgentEnrolled;
import com.revshare.domain.event.AgentTerminated;
import com.revshare.domain.port.in.AgentAffiliation;
import com.revshare.domain.port.out.AgentRepository;
import com.revshare.domain.port.out.DomainEventPublisher;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Begins and ends agent affiliations, and announces both.
 *
 * <p>The write side's second use case. Like {@code RecordClosedTransactionService} it is deliberately dull — load,
 * construct, persist, announce — and for the same reason: every decision worth testing lives in {@link Agent} and
 * {@code SponsorshipPath}, where it can be exercised without a database.
 *
 * <h2>The transaction boundary</h2>
 *
 * <p>Saving the agent and writing the event to the outbox are one transaction, exactly as on the closing path. Without
 * that, an agent could exist in Postgres with no enrolment event ever published, and the read side would carry a
 * permanent hole in the org chart that nothing would ever detect — the dual-write failure the outbox exists to prevent.
 *
 * <h2>The sponsor is loaded, never supplied</h2>
 *
 * <p>{@code enroll} takes a sponsor <em>id</em> and reads that agent to get their path, rather than accepting a path
 * from the caller. Deriving it is the point: {@code SponsorshipPath.sponsoredBy} is what guarantees the new agent's
 * ancestors are exactly their sponsor's ancestors plus the sponsor, and letting a client assert a path instead would
 * make every tier calculation downstream only as trustworthy as the request that created the agent.
 */
@Service
public class AgentAffiliationService implements AgentAffiliation {

    private final AgentRepository agents;
    private final DomainEventPublisher events;
    private final Clock clock;

    public AgentAffiliationService(AgentRepository agents, DomainEventPublisher events, Clock clock) {
        this.agents = agents;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Agent enroll(Enrollment enrollment) {
        // Layer one of the duplicate check, and the one that produces a good error message.
        // It does not catch two enrolments of the same id racing each other; the primary key
        // on `agent` does, and that surfaces as a constraint violation the web layer maps to
        // the same 409. Two layers for the same reason the closing path has two.
        if (agents.findById(enrollment.id()).isPresent()) {
            throw new AgentAlreadyEnrolledException(enrollment.id());
        }

        Agent agent = enrollment
                .sponsor()
                .map(sponsorId -> sponsored(enrollment, sponsorId))
                .orElseGet(() -> Agent.enroll(
                        enrollment.id(),
                        enrollment.firstName(),
                        enrollment.lastName(),
                        enrollment.email(),
                        enrollment.joinedOn()));

        agents.save(agent);

        events.publish(new AgentEnrolled(
                UUID.randomUUID(), clock.instant(), agent.id(), agent.sponsorshipPath(), agent.joinedOn()));

        return agent;
    }

    @Override
    @Transactional
    public Agent terminate(AgentId agentId, LocalDate on) {
        Agent agent = agents.findById(agentId).orElseThrow(() -> new UnknownAgentException(agentId));

        // Throws if the agent is already terminated. Left to the aggregate rather than
        // pre-checked here: the guard belongs with the state it protects, and re-stating it
        // would give one rule two homes that can disagree.
        agent.terminate(on);
        agents.save(agent);

        // The path travels on the event so the read side can find every dashboard that
        // mentions this agent without holding a roster to walk up.
        events.publish(
                new AgentTerminated(UUID.randomUUID(), clock.instant(), agent.id(), agent.sponsorshipPath(), on));

        return agent;
    }

    private Agent sponsored(Enrollment enrollment, AgentId sponsorId) {
        Agent sponsor = agents.findById(sponsorId).orElseThrow(() -> new UnknownSponsorException(sponsorId));

        // A terminated sponsor still sponsors. Their downline keeps its place in the tree and
        // their own upline keeps earning from it; what termination stops is the sponsor
        // collecting, which is a revenue share rule and not an enrolment one. Rejecting the
        // enrolment here would compress the tree by the back door.
        return Agent.enrollSponsoredBy(
                enrollment.id(),
                enrollment.firstName(),
                enrollment.lastName(),
                enrollment.email(),
                enrollment.joinedOn(),
                sponsor.id(),
                sponsor.sponsorshipPath());
    }
}
