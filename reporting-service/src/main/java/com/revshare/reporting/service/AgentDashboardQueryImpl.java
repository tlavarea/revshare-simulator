package com.revshare.reporting.service;

import com.revshare.domain.agent.AgentId;
import com.revshare.reporting.adapter.out.mongo.AgentDashboardMongoRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves a dashboard by primary key.
 *
 * <p>There is very little to this class, and that is the payoff the projector paid for. Every join, grouping and
 * recursive walk that a normalised read of this data would need was done once, on the write of each event; what is left
 * at read time is a single-document lookup and a rendering. If a query method here ever needs an aggregation pipeline,
 * the answer is almost always to project the missing shape rather than to compute it per request.
 *
 * <p>{@code readOnly} is not just a hint — it keeps this off the transactional write path entirely, so a dashboard load
 * never opens a Mongo session that could contend with the projector.
 */
@Service
public class AgentDashboardQueryImpl implements AgentDashboardQuery {

    private final AgentDashboardMongoRepository dashboards;

    public AgentDashboardQueryImpl(AgentDashboardMongoRepository dashboards) {
        this.dashboards = dashboards;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentDashboardView> findByAgentId(AgentId agentId) {
        return dashboards.findById(agentId.toString()).map(AgentDashboardView::from);
    }
}
