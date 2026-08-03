package com.revshare.domain.port.out;

import com.revshare.domain.agent.CapYear;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.transaction.ClosedTransaction;
import com.revshare.domain.transaction.TransactionId;
import java.util.Optional;

/**
 * Driven port for the record of priced closings.
 *
 * <p>Append-only, like {@link RevenueShareLedger} and for the same reason: a priced closing is a fact about the past,
 * and a statement recomputed from history has to produce the numbers it produced originally. Corrections are new
 * compensating rows, never edits.
 *
 * <p>This is also the table {@link ProductionHistory} reads, which is why the two are separate ports over the same
 * data. One records what happened to a closing; the other asks aggregate questions about a window of them. Collapsing
 * them would put a reporting query on the write path's interface.
 */
public interface CommissionSplitRepository {

    /**
     * Records a priced closing.
     *
     * <p>Takes the originating {@link ClosedTransaction} alongside the split because the two carry different halves of
     * the record: the split holds the money, the transaction holds what was sold. Both belong on the row, and neither
     * is derivable from the other.
     *
     * <p>The cap year is passed rather than recomputed so the stored row agrees with the
     * {@link com.revshare.domain.commission.CapProgress} it was priced against, even if the agent's join date is later
     * corrected.
     *
     * @throws DuplicateTransactionException if this transaction has already been priced
     */
    void save(ClosedTransaction transaction, CommissionSplit split, CapYear capYear);

    Optional<CommissionSplit> findByTransactionId(TransactionId transactionId);

    /**
     * Whether this closing has already been priced.
     *
     * <p>The idempotency check on the way in. At-least-once delivery means the same {@code TransactionClosed} event can
     * arrive twice, and pricing it twice would charge an agent's cap twice for one sale.
     */
    boolean exists(TransactionId transactionId);

    /** Signals that a closing was priced twice. The caller should treat it as a no-op. */
    class DuplicateTransactionException extends RuntimeException {
        public DuplicateTransactionException(String message) {
            super(message);
        }

        public DuplicateTransactionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
