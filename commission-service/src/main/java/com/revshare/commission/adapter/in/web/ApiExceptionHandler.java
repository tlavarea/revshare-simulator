package com.revshare.commission.adapter.in.web;

import com.revshare.commission.service.RecordClosedTransactionService;
import com.revshare.domain.port.in.AgentAffiliation;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the write path's failures into responses.
 *
 * <p>{@link ProblemDetail}, the RFC 9457 shape Spring supports natively, matching the read side rather than inventing a
 * second error format for the same system.
 *
 * <p>Only the cases this API can actually produce are mapped, and there is deliberately no catch-all {@code Exception}
 * handler: it would turn an unexpected failure into a tidy 500 and swallow the stack trace that says what broke. Spring
 * already returns a 500 and logs properly for anything not listed here.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * The request named an agent that was never enrolled.
     *
     * <p>404 rather than 400. The JSON was fine and the id was a well-formed UUID; what is missing is the agent, which
     * is a fact about this service rather than about the request. A 400 would send the caller looking at their payload.
     */
    @ExceptionHandler(RecordClosedTransactionService.UnknownAgentException.class)
    ProblemDetail unknownAgent(RecordClosedTransactionService.UnknownAgentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown agent");
        return problem;
    }

    /** Parsed, but describes a closing the domain rejects — a negative amount, or commission exceeding sale price. */
    @ExceptionHandler(RecordClosingRequest.MalformedClosingException.class)
    ProblemDetail malformedClosing(RecordClosingRequest.MalformedClosingException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid closing");
        return problem;
    }

    /**
     * The enrolment names an agent as their own sponsor, or is otherwise refused by the domain.
     *
     * <p>404 for a sponsor that does not exist, on the same reasoning as the unknown agent above: the request is fine,
     * the brokerage simply has no such agent to enrol beneath.
     */
    @ExceptionHandler(EnrollAgentRequest.MalformedEnrollmentException.class)
    ProblemDetail malformedEnrollment(EnrollAgentRequest.MalformedEnrollmentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid enrolment");
        return problem;
    }

    @ExceptionHandler(AgentAffiliation.UnknownSponsorException.class)
    ProblemDetail unknownSponsor(AgentAffiliation.UnknownSponsorException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown sponsor");
        return problem;
    }

    /**
     * The port's own unknown-agent failure, distinct from the closing path's.
     *
     * <p>Two exception types for one condition because each port states its own contract — the same reason
     * {@code CapProgressRepository} and {@code CommissionSplitRepository} declare theirs. They deliberately render
     * identically: a caller has no interest in which use case failed to find the agent.
     */
    @ExceptionHandler(AgentAffiliation.UnknownAgentException.class)
    ProblemDetail unknownAgentOnAffiliation(AgentAffiliation.UnknownAgentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown agent");
        return problem;
    }

    /** Enrolling an id that is already enrolled. */
    @ExceptionHandler(AgentAffiliation.AgentAlreadyEnrolledException.class)
    ProblemDetail alreadyEnrolled(AgentAffiliation.AgentAlreadyEnrolledException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Already enrolled");
        return problem;
    }

    /**
     * A state transition the aggregate refuses — terminating an agent who has already left.
     *
     * <p>409 rather than 400: the request is well-formed and would have been valid earlier. Silently accepting it would
     * let a second, later date overwrite the real departure, which is a fact other calculations depend on.
     */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalTransition(IllegalStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Invalid state transition");
        return problem;
    }

    /**
     * Two enrolments of the same id raced each other past the service's own check.
     *
     * <p>The second layer of the duplicate guard, and the reason it is handled here rather than in the service: a
     * constraint violation marks the Postgres transaction failed, so nothing in it can run afterwards — not even the
     * read that would confirm what happened. By the time this handler sees the exception the transaction has rolled
     * back, which is the only place it is safe to answer at all. Rendered as the same 409 as the check that usually
     * catches it first, because the caller's situation is identical.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail constraintViolation(DataIntegrityViolationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "the record conflicts with one that already exists");
        problem.setTitle("Conflict");
        return problem;
    }

    /** The path variable is not a UUID. */
    @ExceptionHandler(AgentController.MalformedAgentIdException.class)
    ProblemDetail malformedAgentId(AgentController.MalformedAgentIdException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Malformed agent id");
        return problem;
    }

    /**
     * A missing or blank required field.
     *
     * <p>The failing fields are listed rather than summarised. A caller given "validation failed" has to guess; the
     * cost of saying which field and why is a stream and a sort, and it is the difference between a message that
     * resolves the problem and one that only reports it.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * The body could not be read at all: malformed JSON, an unparseable date, or a {@code side} outside the enum.
     *
     * <p>Jackson's own message is not forwarded. It names internal types and line offsets, and for an unknown enum
     * constant it enumerates the valid values alongside a stack of deserialiser context that is noise to a client.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "the request body could not be parsed; expected a closing with a UUID transactionId and agentId, "
                        + "an ISO-8601 closedOn, decimal salePrice and grossCommissionIncome, "
                        + "and a side of LISTING, BUYING or DUAL");
        problem.setTitle("Unreadable request body");
        return problem;
    }

    /**
     * The cap row stayed contended for the whole retry budget.
     *
     * <p>409 and not 500: nothing is broken, and the request is expected to succeed if sent again. The
     * {@code retryable} property says so explicitly rather than leaving a client to infer it from the status — a 409
     * can also mean "and it never will", which is the opposite instruction. No {@code Retry-After}: the contention it
     * would describe clears in milliseconds, and the header's one-second granularity is coarser than the whole wait.
     */
    @ExceptionHandler(TransactionController.CapContentionException.class)
    ProblemDetail capContention(TransactionController.CapContentionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Cap update conflict");
        problem.setProperty("retryable", true);
        return problem;
    }
}
