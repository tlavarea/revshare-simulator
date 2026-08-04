package com.revshare.reporting.service;

import com.revshare.domain.agent.AgentId;
import java.util.Optional;

/**
 * Reads a projected dashboard.
 *
 * <p>The read side's query use case, and the counterpart to {@link DashboardProjector}: one interface writes the
 * projection, one reads it, and nothing else touches the read model. Splitting them is what CQRS means at this scale —
 * the write path folds an unbounded stream and the read path does a primary key lookup, and there is no code in common
 * worth sharing between those two jobs.
 */
public interface AgentDashboardQuery {

    /** @return the agent's dashboard, or empty if no event has ever named them */
    Optional<AgentDashboardView> findByAgentId(AgentId agentId);
}
