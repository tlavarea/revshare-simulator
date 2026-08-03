package com.revshare.commission.adapter.out.persistence;

import com.revshare.commission.adapter.out.persistence.entity.RevenueShareAwardEntity;
import com.revshare.commission.adapter.out.persistence.jpa.RevenueShareAwardJpaRepository;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.port.out.RevenueShareLedger;
import com.revshare.domain.revshare.RevenueShareDistribution;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.domain.shared.Money;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for {@link RevenueShareLedger}. */
@Component
public class RevenueShareLedgerAdapter implements RevenueShareLedger {

    private final RevenueShareAwardJpaRepository awards;

    public RevenueShareLedgerAdapter(RevenueShareAwardJpaRepository awards) {
        this.awards = awards;
    }

    @Override
    @Transactional(readOnly = true)
    public Money totalAwarded(
            AgentId beneficiary, AgentId contributor, RevenueShareTier tier, CapYear contributorCapYear) {
        return Money.of(
                awards.sumAwarded(beneficiary.value(), contributor.value(), tier.name(), contributorCapYear.start()));
    }

    /**
     * Appends every award in a distribution, paid and forfeited alike.
     *
     * <p>Idempotency is guaranteed upstream rather than defended here. Awards are only ever written after
     * {@code CommissionSplitRepository.save} has successfully inserted the closing, and that insert is the one that
     * rejects a replay — so a duplicate distribution cannot reach this method at all.
     *
     * <p>{@code uq_award_transaction_beneficiary} therefore stays as a database-level backstop and is deliberately
     * <em>not</em> caught. Recovering from it in application code would be futile anyway: a constraint violation aborts
     * the enclosing Postgres transaction, so any read attempted afterwards to work out what happened would itself fail.
     * If it ever fires, the right outcome is a rolled-back transaction and a loud error.
     */
    @Override
    @Transactional
    public void record(RevenueShareDistribution distribution, CapYear contributorCapYear) {
        if (distribution.isEmpty()) {
            return;
        }

        List<RevenueShareAwardEntity> entities = distribution.awards().stream()
                .map(award -> PersistenceMapper.toEntity(award, contributorCapYear, UUID.randomUUID()))
                .toList();

        awards.saveAll(entities);
    }
}
