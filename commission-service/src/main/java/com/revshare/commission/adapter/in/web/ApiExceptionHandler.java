package com.revshare.commission.adapter.in.web;

import com.revshare.commission.service.RecordClosedTransactionService;
import java.util.List;
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
