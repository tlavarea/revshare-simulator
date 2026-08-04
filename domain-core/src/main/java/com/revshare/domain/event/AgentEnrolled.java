package com.revshare.domain.event;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.SponsorshipPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An agent joined the brokerage, in a fixed place in the sponsorship tree.
 *
 * <p>The event that makes an agent visible to the rest of the system before they have done anything. Until this
 * existed, the read side learned of an agent only when money moved — a closing they sold or an award they earned — so
 * an agent who had been sponsored but never produced was indistinguishable from one who did not exist. That is accurate
 * for earnings and wrong for an org chart, and it is the gap this event closes.
 *
 * <h2>Why the whole path travels, not just the sponsor</h2>
 *
 * <p>{@code sponsorshipPath} is carried in full so a consumer can place this agent at every depth at once: the ancestor
 * at index 0 gains a tier 1 member, index 1 a tier 2 member, and so on. The alternative — sending only the sponsor id
 * and letting the read side walk upward — would require it to hold a roster of paths and traverse it per event, which
 * is the recursive-lookup work the materialised path exists to abolish. Sending the path keeps the read side additive.
 *
 * <p>It also keeps the record honest under replay. The path is frozen at enrolment and never rewritten, so the copy in
 * this event is not a snapshot that can drift — it is the value, for the life of the agent.
 *
 * <h2>Partitioned on the agent themselves</h2>
 *
 * <p>Keyed by the enrolling agent rather than by their sponsor, so this event stays ordered against the agent's own
 * later lifecycle — {@link AgentTerminated} above all. Ordering against an <em>ancestor's</em> events is not needed and
 * could not be had anyway: one enrolment concerns up to five ancestors, so no single ancestor can own its ordering,
 * which is the same reason {@link RevenueShareDistributed} keys on the contributor.
 */
public record AgentEnrolled(
        UUID eventId, Instant occurredAt, AgentId agentId, SponsorshipPath sponsorshipPath, LocalDate joinedOn)
        implements DomainEvent {

    public AgentEnrolled {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(sponsorshipPath, "sponsorshipPath must not be null");
        Objects.requireNonNull(joinedOn, "joinedOn must not be null");

        if (sponsorshipPath.ancestorsNearestFirst().contains(agentId)) {
            throw new IllegalArgumentException("agent " + agentId + " appears in their own sponsorship path");
        }
    }

    /** The sponsor who enrolled this agent, empty for an agent at the top of a tree. */
    public java.util.Optional<AgentId> sponsorId() {
        return sponsorshipPath.sponsor();
    }

    @Override
    public String partitionKey() {
        return agentId.toString();
    }
}
