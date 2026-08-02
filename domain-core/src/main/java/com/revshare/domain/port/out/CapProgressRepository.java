package com.revshare.domain.port.out;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.commission.CapProgress;
import java.util.Optional;

/**
 * Driven port for cap progress persistence.
 *
 * <p><strong>The implementation must serialize concurrent updates to the same (agent, cap year).</strong> Cap progress
 * is read-modify-write, and two closings priced in parallel for one agent would otherwise both read the same prior
 * balance, both compute a full split, and both write, losing one contribution and letting the agent earn past the cap.
 * The money involved is real and the error is invisible in the data afterwards.
 *
 * <p>{@link #findOrOpen} exists so the caller never has to decide whether a row is missing because it is a new cap year
 * or because of a race. The adapter resolves that with an upsert under a unique constraint on (agent, cap year start),
 * which is also the index that makes the lookup a single-row primary-key hit on the hot path.
 */
public interface CapProgressRepository {

    /** Loads progress for the given cap year, creating an opening balance if none exists. */
    CapProgress findOrOpen(AgentId agentId, CapYear capYear);

    Optional<CapProgress> find(AgentId agentId, CapYear capYear);

    /**
     * Persists updated progress.
     *
     * @throws ConcurrentCapUpdateException if another writer advanced this aggregate first; the caller should re-read
     *     and re-price
     */
    void save(CapProgress progress);

    /** Signals a lost optimistic-locking race. Retryable by re-reading and recomputing. */
    class ConcurrentCapUpdateException extends RuntimeException {
        public ConcurrentCapUpdateException(String message) {
            super(message);
        }

        public ConcurrentCapUpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
