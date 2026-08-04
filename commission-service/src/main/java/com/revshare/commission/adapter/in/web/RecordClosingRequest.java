package com.revshare.commission.adapter.in.web;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import com.revshare.domain.transaction.TransactionId;
import com.revshare.domain.transaction.TransactionSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A closing, as a client sends it.
 *
 * <p>A separate shape from {@link ClosedTransaction} rather than binding the domain record directly. Jackson would
 * happily construct one, but the compact constructor throws on invalid input, so a negative sale price would surface as
 * a message-conversion failure rather than as an answer about which field was wrong — and binding the domain type would
 * make every field name in the core part of the published request contract.
 *
 * <h2>The transaction id is the client's to assign</h2>
 *
 * <p>Required, not generated here. It is the idempotency key the whole write path is built on:
 * {@code RecordClosedTransaction} is idempotent on transaction id, and the primary key on {@code commission_split} is
 * the backstop for two deliveries racing. A server-generated id would defeat all of it — a client whose request timed
 * out has no way to ask "did that one land?", and retrying would record the same sale a second time and charge the
 * agent's cap twice. Making the caller name the closing is what turns a retry into a replay.
 *
 * <h2>What is validated here, and what is not</h2>
 *
 * <p>Only presence and shape: a missing field is a fact about the request, and letting it through to be an
 * {@code NullPointerException} in a domain constructor would be a 500 for what is plainly a 400. Everything else —
 * amounts being positive, gross commission not exceeding sale price — is a domain invariant and is left to
 * {@link ClosedTransaction} to enforce. Re-stating those as annotations would put a business rule in an adapter and
 * give it two homes that could drift apart; {@link #toClosedTransaction()} translates the domain's refusal into a 400
 * instead.
 */
public record RecordClosingRequest(
        @NotNull(message = "transactionId is required; it is the idempotency key for this closing")
        UUID transactionId,

        @NotNull(message = "agentId is required") UUID agentId,
        @NotNull(message = "closedOn is required") LocalDate closedOn,
        @NotNull(message = "salePrice is required") BigDecimal salePrice,

        @NotNull(message = "grossCommissionIncome is required")
        BigDecimal grossCommissionIncome,

        @NotNull(message = "side is required; one of LISTING, BUYING, DUAL")
        TransactionSide side,

        @NotBlank(message = "propertyReference is required") String propertyReference) {

    /**
     * Builds the domain input, translating its rejection into a 400.
     *
     * <p>The core validates on construction and throws {@link IllegalArgumentException}. Caught and rethrown as
     * {@link MalformedClosingException} rather than left to propagate, because a bare {@code IllegalArgumentException}
     * mapped globally to 400 would also convert genuine internal bugs into tidy client errors. Wrapping it at the one
     * place it can legitimately arise keeps that mapping narrow.
     */
    public ClosedTransaction toClosedTransaction() {
        try {
            return new ClosedTransaction(
                    TransactionId.of(transactionId),
                    AgentId.of(agentId),
                    closedOn,
                    Money.of(salePrice),
                    Money.of(grossCommissionIncome),
                    side,
                    propertyReference);
        } catch (IllegalArgumentException e) {
            throw new MalformedClosingException(e.getMessage(), e);
        }
    }

    /** The request parsed, but describes a closing the domain will not accept. */
    public static class MalformedClosingException extends RuntimeException {
        public MalformedClosingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
