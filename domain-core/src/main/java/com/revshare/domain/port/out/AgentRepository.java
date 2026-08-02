package com.revshare.domain.port.out;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Driven port for agent persistence.
 *
 * <p>Declared in the core and implemented by an adapter, so the dependency points inward: the JPA module knows about
 * this interface, this interface knows nothing about JPA. That inversion is the whole point of the arrangement, and it
 * is what lets every rule in this module be tested against a hand-written in-memory implementation.
 *
 * <p>The method names are domain questions, not table operations. There is no {@code findAll} because nothing in the
 * domain ever legitimately wants every agent.
 */
public interface AgentRepository {

    Optional<Agent> findById(AgentId id);

    /**
     * Loads the agents in an agent's upline, within revenue share reach.
     *
     * <p>A single call rather than a loop of {@link #findById}, because the adapter can satisfy it with one query
     * against the stored sponsorship path, and distribution needs all of them or none.
     */
    Map<AgentId, Agent> findRevenueShareUplineOf(AgentId agentId);

    /** The agents this agent personally sponsored. Their tier 1. */
    List<Agent> findFrontlineOf(AgentId sponsorId);

    void save(Agent agent);
}
