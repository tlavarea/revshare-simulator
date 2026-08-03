package com.revshare.commission.adapter.out.persistence;

import com.revshare.commission.adapter.out.persistence.entity.CapProgressEntity;
import com.revshare.commission.adapter.out.persistence.jpa.CapProgressJpaRepository;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionPlan;
import com.revshare.domain.port.out.CapProgressRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link CapProgressRepository}.
 *
 * <p>This is the adapter where concurrency actually matters, and the two racing scenarios are handled by different
 * mechanisms because they are different problems:
 *
 * <ul>
 *   <li><strong>Two writers advancing the same cap year.</strong> The {@code @Version} check catches the loser,
 *       surfacing as {@link OptimisticLockingFailureException} and re-thrown as the retryable
 *       {@code ConcurrentCapUpdateException}. Without it one contribution is silently lost and the agent earns past
 *       their cap.
 *   <li><strong>Two writers opening the same new cap year.</strong> Both find no row and both try to insert. Handled by
 *       an upsert rather than by catching the constraint violation, because in Postgres that violation aborts the
 *       entire transaction — see {@link #open}.
 * </ul>
 *
 * <p>{@code uq_cap_progress_agent_year} underwrites the second case. Without it the losing writer would quietly create
 * a duplicate row, letting the agent contribute twice the cap in one year — invisible afterwards, because both rows
 * look individually valid.
 */
@Component
public class CapProgressRepositoryAdapter implements CapProgressRepository {

    private final CapProgressJpaRepository capProgress;
    private final CommissionPlan commissionPlan;

    public CapProgressRepositoryAdapter(CapProgressJpaRepository capProgress, CommissionPlan commissionPlan) {
        this.capProgress = capProgress;
        this.commissionPlan = commissionPlan;
    }

    @Override
    @Transactional
    public CapProgress findOrOpen(AgentId agentId, CapYear capYear) {
        return find(agentId, capYear).orElseGet(() -> open(agentId, capYear));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CapProgress> find(AgentId agentId, CapYear capYear) {
        return capProgress
                .findByAgentIdAndCapYearStart(agentId.value(), capYear.start())
                .map(PersistenceMapper::toDomain);
    }

    @Override
    @Transactional
    public void save(CapProgress progress) {
        CapProgressEntity entity = capProgress
                .findByAgentIdAndCapYearStart(
                        progress.agentId().value(), progress.capYear().start())
                .orElseThrow(() -> new ConcurrentCapUpdateException("cap progress for agent " + progress.agentId()
                        + " in " + progress.capYear() + " disappeared between read and write"));

        entity.setContributed(progress.contributed().amount());

        try {
            // Flush here rather than at transaction commit. The version check has to fail
            // inside this method for the exception to be translated, otherwise it surfaces
            // from the commit as something the caller cannot associate with this aggregate.
            capProgress.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentCapUpdateException(
                    "cap progress for agent " + progress.agentId() + " in " + progress.capYear()
                            + " was advanced by another writer; re-read and re-price",
                    e);
        }
    }

    /**
     * Opens a cap year, tolerating another writer opening it at the same instant.
     *
     * <p>The upsert is what makes this safe. Inserting and catching the duplicate-key error would abort the enclosing
     * Postgres transaction, and the recovery read would then fail with "current transaction is aborted" rather than
     * returning the winner's row. Doing it as {@code ON CONFLICT DO NOTHING} means the error never arises, so whichever
     * writer loses simply reads back what the winner wrote.
     */
    private CapProgress open(AgentId agentId, CapYear capYear) {
        CapProgress opening = CapProgress.opening(agentId, capYear, commissionPlan);

        capProgress.insertIfAbsent(
                UUID.randomUUID(),
                agentId.value(),
                capYear.start(),
                capYear.endExclusive(),
                capYear.ordinal(),
                opening.capAmount().amount());

        return find(agentId, capYear)
                .orElseThrow(() -> new ConcurrentCapUpdateException(
                        "cap year " + capYear + " for agent " + agentId + " could not be opened or read back"));
    }
}
