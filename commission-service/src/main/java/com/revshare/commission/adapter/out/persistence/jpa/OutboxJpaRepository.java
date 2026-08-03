package com.revshare.commission.adapter.out.persistence.jpa;

import com.revshare.commission.adapter.out.persistence.entity.OutboxEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Claims a batch of unpublished events for this relay, skipping any another relay already holds.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes a second relay instance safe: without it both would read the same
     * rows and publish them twice. With it, the second instance takes the next unlocked rows instead of blocking, so
     * neither stalls and no row is claimed twice.
     *
     * <p><strong>This buys duplicate suppression, not cross-instance ordering.</strong> If two relays run, the one that
     * claimed later rows may publish before the one holding earlier ones, and two events for the same agent could reach
     * Kafka out of order. Per-agent ordering is a real requirement here — cap progress is cumulative — so the
     * deployment assumption is a single active relay. Scaling out would mean sharding the claim by
     * {@code partition_key} so each relay owns a disjoint set of keys and ordering holds within each. The lock is kept
     * regardless, because an accidental second instance should produce duplicates a consumer can absorb rather than a
     * silent double-publish.
     *
     * <p>Runs against {@code ix_outbox_unpublished}, the partial index over unpublished rows, so the claim stays a
     * small index scan no matter how much published history sits behind it.
     */
    @Query(value = """
                    SELECT * FROM outbox
                    WHERE published_at IS NULL
                    ORDER BY occurred_at ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    List<OutboxEntity> claimUnpublished(@Param("batchSize") int batchSize);
}
