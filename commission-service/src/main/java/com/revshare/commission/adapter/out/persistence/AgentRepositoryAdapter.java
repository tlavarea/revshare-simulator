package com.revshare.commission.adapter.out.persistence;

import com.revshare.commission.adapter.out.persistence.entity.AgentEntity;
import com.revshare.commission.adapter.out.persistence.jpa.AgentJpaRepository;
import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.port.out.AgentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for {@link AgentRepository}. */
@Component
public class AgentRepositoryAdapter implements AgentRepository {

    private final AgentJpaRepository agents;

    public AgentRepositoryAdapter(AgentJpaRepository agents) {
        this.agents = agents;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Agent> findById(AgentId id) {
        return agents.findById(id.value()).map(PersistenceMapper::toDomain);
    }

    /**
     * Loads the whole upline in a single query.
     *
     * <p>Reads the ids from the agent's own stored sponsorship path rather than walking sponsor links upward. That is
     * the correctness-critical choice, not just the fast one: an agent whose sponsor has left the brokerage still sits
     * at their original depth beneath everyone above them, and a live walk would either break on the missing link or
     * compress the tree and pay the wrong tier rate.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<AgentId, Agent> findRevenueShareUplineOf(AgentId agentId) {
        Optional<AgentEntity> agent = agents.findById(agentId.value());
        if (agent.isEmpty()) {
            return Map.of();
        }

        List<UUID> upline = PersistenceMapper.toDomain(agent.get()).sponsorshipPath().revenueShareUpline().stream()
                .map(AgentId::value)
                .toList();
        if (upline.isEmpty()) {
            return Map.of();
        }

        // Preserve upline order in the returned map so callers can reason about tier
        // position by iteration if they need to, rather than relying on hash order.
        Map<UUID, Agent> loaded = new LinkedHashMap<>();
        agents.findAllByIdIn(upline).forEach(entity -> loaded.put(entity.getId(), PersistenceMapper.toDomain(entity)));

        Map<AgentId, Agent> result = new LinkedHashMap<>();
        upline.forEach(id -> {
            Agent found = loaded.get(id);
            if (found != null) {
                result.put(AgentId.of(id), found);
            }
        });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Agent> findFrontlineOf(AgentId sponsorId) {
        return agents.findAllBySponsorId(sponsorId.value()).stream()
                .map(PersistenceMapper::toDomain)
                .toList();
    }

    /**
     * Inserts a new agent, or applies the mutable half of an existing one.
     *
     * <p>Only status, elite status and termination date can change. Identity, join date and sponsorship path are
     * immutable in the domain, so they are never written on update — re-parenting an agent would silently invalidate
     * every tier calculation beneath them.
     */
    @Override
    @Transactional
    public void save(Agent agent) {
        Optional<AgentEntity> existing = agents.findById(agent.id().value());
        if (existing.isPresent()) {
            existing.get()
                    .applyMutableState(
                            agent.status().name(),
                            agent.eliteStatus().name(),
                            agent.terminatedOn().orElse(null));
            return;
        }
        agents.save(PersistenceMapper.toEntity(agent));
    }
}
