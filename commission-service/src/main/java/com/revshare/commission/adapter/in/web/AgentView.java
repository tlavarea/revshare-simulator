package com.revshare.commission.adapter.in.web;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import java.time.LocalDate;
import java.util.List;

/**
 * An agent as the write side serves them.
 *
 * <p>Strings and primitives, not the {@link Agent} aggregate. Serialising the aggregate would publish its internals as
 * a wire contract and — more to the point — would hand callers a mutable domain object's shape, which is exactly the
 * thing the aggregate exists to keep behind guarded methods.
 *
 * <p>The {@code sponsorshipPath} is included because a caller who has just enrolled an agent has no other way to see
 * where the brokerage decided they sit, and it is derived server-side rather than supplied. Nearest ancestor first, so
 * index 0 is the sponsor and index <em>n</em> is tier <em>n+1</em> — the same orientation the domain uses.
 */
public record AgentView(
        String agentId,
        String firstName,
        String lastName,
        String email,
        LocalDate joinedOn,
        String status,
        String eliteStatus,
        LocalDate terminatedOn,
        String sponsorId,
        List<String> sponsorshipPath) {

    public static AgentView from(Agent agent) {
        return new AgentView(
                agent.id().toString(),
                agent.firstName(),
                agent.lastName(),
                agent.email(),
                agent.joinedOn(),
                agent.status().name(),
                agent.eliteStatus().name(),
                agent.terminatedOn().orElse(null),
                agent.sponsorId().map(AgentId::toString).orElse(null),
                agent.sponsorshipPath().ancestorsNearestFirst().stream()
                        .map(AgentId::toString)
                        .toList());
    }
}
