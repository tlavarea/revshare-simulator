package com.revshare.commission.adapter.out.persistence;

import com.revshare.commission.adapter.out.persistence.jpa.CommissionSplitJpaRepository;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.port.out.CommissionSplitRepository;
import com.revshare.domain.transaction.ClosedTransaction;
import com.revshare.domain.transaction.TransactionId;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for {@link CommissionSplitRepository}. */
@Component
public class CommissionSplitRepositoryAdapter implements CommissionSplitRepository {

    private final CommissionSplitJpaRepository splits;

    public CommissionSplitRepositoryAdapter(CommissionSplitJpaRepository splits) {
        this.splits = splits;
    }

    /**
     * Inserts the priced closing.
     *
     * <p>The primary key is the client-assigned transaction id, so a duplicate insert is rejected by the database
     * rather than overwriting the original pricing. That makes the key itself the last line of idempotency defence: the
     * use case checks {@link #exists} first, but two concurrent deliveries of the same event both pass that check, and
     * only one can win here.
     */
    @Override
    @Transactional
    public void save(ClosedTransaction transaction, CommissionSplit split, CapYear capYear) {
        int inserted = splits.insertIfAbsent(
                split.transactionId().value(),
                split.agentId().value(),
                split.closedOn(),
                capYear.start(),
                transaction.salePrice().amount(),
                split.grossCommissionIncome().amount(),
                split.agentEarnings().amount(),
                split.companyEarnings().amount(),
                split.capContribution().amount(),
                split.postCapFeeCharged().amount(),
                split.revenueShareEligibleGross().amount(),
                split.pricedUnderPostCapFee(),
                split.reachedCapOnThisTransaction(),
                transaction.side().name(),
                transaction.propertyReference());

        if (inserted == 0) {
            // Another delivery priced this closing first. Raised as an exception rather than
            // ignored, because the caller's cap calculation was made against a balance that
            // the winner has since advanced, and applying it now would double-charge.
            throw new DuplicateTransactionException(
                    "transaction " + split.transactionId() + " has already been priced");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommissionSplit> findByTransactionId(TransactionId transactionId) {
        return splits.findById(transactionId.value()).map(PersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(TransactionId transactionId) {
        return splits.existsById(transactionId.value());
    }
}
