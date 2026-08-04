package com.revshare.domain.event;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.SponsorshipPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An agent's affiliation with the brokerage ended.
 *
 * <p>The counterpart to {@link AgentEnrolled}, and necessary for the same reason: without it a roster built from
 * enrolments only ever grows, so a downline would show every agent who ever joined as though they were still there.
 * That is a different kind of wrong from the gap {@code AgentEnrolled} closes, and no less misleading.
 *
 * <h2>What ending an affiliation does not do</h2>
 *
 * <p>It does not remove the agent from anyone's downline, and consumers must not treat it that way. The sponsorship
 * hierarchy does not compress: if A sponsors B and B sponsors C, B leaving keeps C at tier 2 beneath A forever, and A
 * keeps earning at the tier 2 rate on C's production. A read model that deleted B from A's tier 1 would be asserting a
 * tree shape the write side does not have — and would misreport C's depth the moment it tried to recompute it.
 *
 * <p>So this event marks a member departed rather than removing them. The distinction is the whole reason
 * {@link SponsorshipPath} is a frozen materialised path; see its Javadoc.
 *
 * <p>What it does mean is that the agent stops collecting. A terminated beneficiary forfeits their share with
 * {@code ForfeitReason.BENEFICIARY_NOT_AFFILIATED}, which the write side decides — this event announces the fact, it
 * does not carry the consequence.
 *
 * <p>The path travels here for the same reason it travels on enrolment: a consumer must be able to find every dashboard
 * that mentions this agent without holding a roster to walk.
 */
public record AgentTerminated(
        UUID eventId, Instant occurredAt, AgentId agentId, SponsorshipPath sponsorshipPath, LocalDate terminatedOn)
        implements DomainEvent {

    public AgentTerminated {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(sponsorshipPath, "sponsorshipPath must not be null");
        Objects.requireNonNull(terminatedOn, "terminatedOn must not be null");

        if (sponsorshipPath.ancestorsNearestFirst().contains(agentId)) {
            throw new IllegalArgumentException("agent " + agentId + " appears in their own sponsorship path");
        }
    }

    @Override
    public String partitionKey() {
        return agentId.toString();
    }
}
