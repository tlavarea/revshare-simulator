package com.revshare.commission.adapter.in.web;

import com.revshare.domain.port.in.RecordClosedTransaction;
import com.revshare.domain.port.out.CapProgressRepository;
import com.revshare.domain.transaction.ClosedTransaction;
import jakarta.validation.Valid;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The write side's HTTP surface: record a closing.
 *
 * <p>One endpoint, because there is one use case. {@link RecordClosedTransaction} is the driving port declared in the
 * core, and this class does what a driving adapter should: parse, translate, call it, and turn the outcome into a
 * status code. No arithmetic, no branching on domain state, no transaction of its own.
 *
 * <h2>201 or 200, and why the difference is worth having</h2>
 *
 * <p>A newly priced closing is <strong>201 Created</strong>; a redelivery of one already recorded is <strong>200
 * OK</strong>. Both carry the same receipt. Idempotency is the contract of the port rather than a courtesy, and putting
 * it in the status line means a client can tell "I recorded this" from "this was already recorded" without inspecting
 * the body — which matters most in exactly the case that produces it, a retry after a timeout where the client does not
 * know whether the first attempt landed.
 *
 * <p>No {@code Location} header on the 201. There is nothing to point it at: this service exposes no read of a
 * transaction, and the projection that can answer for one lives in {@code reporting-service} behind a different
 * endpoint and an eventual-consistency lag. A header promising a resource that may 404 for another second is worse than
 * no header.
 */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    /**
     * How many times a closing is re-priced against a concurrent winner before the caller is asked to retry.
     *
     * <p>Five is chosen against what actually contends. A conflict needs two closings for the <em>same</em> agent in
     * flight at the same instant; each retry re-reads the winner's cap and re-prices in single-digit milliseconds, so
     * five attempts covers far more simultaneity than one agent's deal flow can produce. Unbounded retrying would
     * convert a genuinely stuck row into a hung request holding a connection from a pool deliberately sized small.
     */
    private static final int MAX_ATTEMPTS = 5;

    private final RecordClosedTransaction recordClosedTransaction;

    public TransactionController(RecordClosedTransaction recordClosedTransaction) {
        this.recordClosedTransaction = recordClosedTransaction;
    }

    /**
     * Prices a closing, advances the agent's cap, and distributes the revenue share it funds.
     *
     * <p>Every outcome the caller can cause has its own status: 400 for a request the domain will not accept, 404 for
     * an agent this service has never enrolled, 409 when the cap row stayed contended. A 404 rather than a 400 for the
     * unknown agent because the request is well-formed — the agent is simply not here, which is a fact about this
     * service's state and not about the caller's JSON.
     */
    @PostMapping
    public ResponseEntity<ClosingReceiptView> record(@Valid @RequestBody RecordClosingRequest request) {
        ClosedTransaction closing = request.toClosedTransaction();
        RecordClosedTransaction.Receipt receipt = recordWithRetry(closing);

        return ResponseEntity.status(receipt.alreadyRecorded() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ClosingReceiptView.from(receipt));
    }

    /**
     * Records the closing, re-pricing it if another writer advanced the cap first.
     *
     * <p>The retry belongs here and cannot live anywhere further in. {@code CapProgressRepository} states that a
     * conflict is for the caller to resolve by re-reading and retrying, and the reason it is stated rather than handled
     * is that the whole recording — cap, split, ledger and outbox — is one transaction. Retrying inside it would reuse
     * a transaction Postgres has already marked failed; the attempt has to be a new one, which means the loop must sit
     * outside the {@code @Transactional} boundary. The controller is the first place that is true.
     *
     * <p>This is mechanical recovery, not a decision. The optimistic lock exists to convert a silent lost update into a
     * loud retryable failure, and re-pricing against the winner's balance is the only correct response to it — which is
     * precisely why it is safe to do without asking the client. What would not be safe is doing it without a bound.
     *
     * <p>Both exception types are caught for the same reason {@code CapProgressConcurrencyIT} catches both: the adapter
     * raises {@code ConcurrentCapUpdateException} when it detects the conflict itself, and Hibernate's own version
     * check surfaces as {@code OptimisticLockingFailureException} when the flush is what discovers it.
     */
    private RecordClosedTransaction.Receipt recordWithRetry(ClosedTransaction closing) {
        for (int attempt = 1; ; attempt++) {
            try {
                return recordClosedTransaction.record(closing);
            } catch (CapProgressRepository.ConcurrentCapUpdateException | OptimisticLockingFailureException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new CapContentionException(MAX_ATTEMPTS, e);
                }
                backOff(attempt);
            }
        }
    }

    /**
     * Waits a randomised moment before re-pricing.
     *
     * <p>Jittered rather than fixed, and randomised rather than escalating. Two threads that collided are in lockstep
     * by definition; retrying both immediately, or both after the same delay, reproduces the collision. A few
     * milliseconds of independent noise is enough to separate them, and it is short enough that a caller cannot
     * perceive it.
     */
    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5L, 5L + (10L * attempt)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CapContentionException(attempt, e);
        }
    }

    /** The agent's cap row stayed contended for the whole retry budget. */
    public static class CapContentionException extends RuntimeException {
        public CapContentionException(int attempts, Throwable cause) {
            super(
                    "could not price this closing after " + attempts
                            + " attempts; another closing for the same agent kept winning the cap update",
                    cause);
        }
    }
}
