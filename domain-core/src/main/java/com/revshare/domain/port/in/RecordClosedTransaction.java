package com.revshare.domain.port.in;

import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.revshare.RevenueShareDistribution;
import com.revshare.domain.transaction.ClosedTransaction;
import java.util.Objects;

/**
 * Driving port: price a closing and distribute the revenue share it funds.
 *
 * <p>The one way into the write side. Whatever calls it — a REST controller, a Kafka consumer, a bulk loader replaying
 * generated seed data — talks to this interface and not to the calculators, so the orchestration and its transaction
 * boundary exist in exactly one place.
 *
 * <p>Declared in the core rather than in the service module so the dependency still points inward: a driving adapter
 * depends on this, and this depends on nothing.
 */
public interface RecordClosedTransaction {

    /**
     * Prices the closing, advances the agent's cap, distributes revenue share up the contributor's frozen sponsorship
     * path, and records the resulting events.
     *
     * <p><strong>Idempotent on transaction id.</strong> Recording the same closing twice returns the original outcome
     * with {@link Receipt#alreadyRecorded()} set, rather than charging the agent's cap a second time for one sale.
     * Callers redelivering an at-least-once event stream depend on this.
     */
    Receipt record(ClosedTransaction transaction);

    /**
     * Everything the closing produced, returned together.
     *
     * <p>Grouped rather than returned piecemeal because the three are only meaningful with respect to one another: the
     * split determines the cap progress, and the cap progress determines how much of the closing could fund the
     * distribution.
     */
    record Receipt(
            CommissionSplit split,
            CapProgress progressAfter,
            RevenueShareDistribution distribution,
            boolean alreadyRecorded) {

        public Receipt {
            Objects.requireNonNull(split, "split must not be null");
            Objects.requireNonNull(progressAfter, "progressAfter must not be null");
            Objects.requireNonNull(distribution, "distribution must not be null");
        }

        /** True when this closing is the one that took the agent to their cap. */
        public boolean reachedCap() {
            return split.reachedCapOnThisTransaction();
        }
    }
}
