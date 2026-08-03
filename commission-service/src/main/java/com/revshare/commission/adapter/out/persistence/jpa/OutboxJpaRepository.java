package com.revshare.commission.adapter.out.persistence.jpa;

import com.revshare.commission.adapter.out.persistence.entity.OutboxEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxJpaRepository extends JpaRepository<OutboxEntity, UUID> {

    /**
     * The relay's poll: the oldest unpublished events, in the order they occurred.
     *
     * <p>Matches {@code ix_outbox_unpublished}, a partial index over unpublished rows only, so the poll stays a small
     * index scan no matter how much published history accumulates behind it.
     *
     * <p>Ordering by {@code occurredAt} preserves per-aggregate causality on the way out: a cap-threshold event cannot
     * overtake the commission event that caused it.
     */
    List<OutboxEntity> findByPublishedAtIsNullOrderByOccurredAtAsc(Limit limit);

    long countByPublishedAtIsNull();
}
