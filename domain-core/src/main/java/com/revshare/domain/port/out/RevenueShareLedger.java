package com.revshare.domain.port.out;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.revshare.RevenueShareDistribution;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.domain.shared.Money;

/**
 * Driven port for the record of what revenue share has been paid.
 *
 * <p>Append-only. Awards are never updated or deleted, because the ledger is what a commission statement is
 * reconstructed from and an edited row makes a past statement unreproducible. A correction is a new compensating entry.
 *
 * <p>{@link #totalAwarded} is the hot read: it is consulted once per beneficiary per closing, so up to five times per
 * transaction, to work out how much of the annual tier maximum is left. The adapter needs a covering index on
 * (beneficiary, contributor, tier, cap year) for it, and the query is a sum over a narrow range rather than a scan.
 */
public interface RevenueShareLedger {

    /**
     * How much this beneficiary has already drawn at this tier, from this one contributor, within that contributor's
     * cap year.
     *
     * <p>Scoped to the contributor because the annual maximum is per contributing agent, not per beneficiary.
     * Aggregating across contributors here would cap a large downline at one agent's worth of earnings.
     */
    Money totalAwarded(AgentId beneficiary, AgentId contributor, RevenueShareTier tier, CapYear contributorCapYear);

    /**
     * Appends every award in a distribution.
     *
     * <p>Must be idempotent on transaction id: the read side consumes at least once, and a redelivered event must not
     * double-pay an upline.
     */
    /**
     * Takes the contributor's cap year explicitly rather than deriving it from the closing date. The allowance window
     * belongs to the contributor, and resolving it needs that agent's join date — a lookup the caller has already done
     * to price the closing, and which the ledger has no business repeating.
     */
    void record(RevenueShareDistribution distribution, CapYear contributorCapYear);
}
