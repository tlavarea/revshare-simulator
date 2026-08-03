package com.revshare.commission.adapter.out.persistence.jpa;

import com.revshare.commission.adapter.out.persistence.entity.CapProgressEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CapProgressJpaRepository extends JpaRepository<CapProgressEntity, UUID> {

    /**
     * The hot lookup on the write path, satisfied by {@code uq_cap_progress_agent_year}.
     *
     * <p>Deliberately <em>not</em> a pessimistic lock. Contention only arises when one agent closes two deals
     * concurrently, which is rare, so the entity's {@code @Version} handles it optimistically and the caller retries —
     * cheaper in aggregate than locking a row on every single closing.
     */
    Optional<CapProgressEntity> findByAgentIdAndCapYearStart(UUID agentId, LocalDate capYearStart);

    /**
     * Opens a cap year if it does not already exist, as a single atomic upsert.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an insert wrapped in a try/catch, and the distinction is not
     * stylistic. In Postgres a constraint violation <em>aborts the whole transaction</em>: every subsequent statement
     * fails with "current transaction is aborted, commands ignored until end of transaction block". So the obvious
     * recovery — catch the duplicate-key error, then re-read the row the winner inserted — cannot work, because the
     * re-read is itself a subsequent statement in a poisoned transaction.
     *
     * <p>An upsert never raises the error in the first place, so the transaction stays alive and the caller can simply
     * read the row afterwards, whichever writer created it.
     *
     * @return 1 if this call opened the cap year, 0 if another writer already had
     */
    @Modifying
    @Query(value = """
                    INSERT INTO cap_progress (
                        id, agent_id, cap_year_start, cap_year_end_exclusive,
                        cap_year_ordinal, contributed, cap_amount, version)
                    VALUES (:id, :agentId, :capYearStart, :capYearEndExclusive,
                            :capYearOrdinal, 0, :capAmount, 0)
                    ON CONFLICT (agent_id, cap_year_start) DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("agentId") UUID agentId,
            @Param("capYearStart") LocalDate capYearStart,
            @Param("capYearEndExclusive") LocalDate capYearEndExclusive,
            @Param("capYearOrdinal") int capYearOrdinal,
            @Param("capAmount") BigDecimal capAmount);
}
