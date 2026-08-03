package com.revshare.commission.adapter.out.persistence.jpa;

import com.revshare.commission.adapter.out.persistence.entity.AgentEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentJpaRepository extends JpaRepository<AgentEntity, UUID> {

    /**
     * Loads a whole upline in one query.
     *
     * <p>The ids come from the agent's own stored path, so this is a primary-key {@code IN} lookup rather than a
     * recursive walk. Issuing it as one query instead of a loop of {@code findById} is the difference between one round
     * trip and five per closing.
     */
    List<AgentEntity> findAllByIdIn(Collection<UUID> ids);

    /** Backed by {@code ix_agent_sponsor_id}. */
    List<AgentEntity> findAllBySponsorId(UUID sponsorId);
}
