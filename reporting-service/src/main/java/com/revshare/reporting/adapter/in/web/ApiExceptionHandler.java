package com.revshare.reporting.adapter.in.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the controller's failures into responses.
 *
 * <p>{@link ProblemDetail}, the RFC 9457 shape Spring supports natively, rather than a bespoke error record. It costs
 * nothing over a hand-rolled type and means a client already speaking the standard needs no special case for this
 * service.
 *
 * <p>Only the cases this API can actually produce are mapped. There is no catch-all {@code Exception} handler on
 * purpose: it would convert an unexpected failure into a tidy 500 and swallow the stack trace that says what broke,
 * which is exactly the information worth keeping when something unforeseen happens. Spring's default handling already
 * returns a 500 and logs properly.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AgentDashboardController.DashboardNotFoundException.class)
    ProblemDetail dashboardNotFound(AgentDashboardController.DashboardNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Dashboard not found");
        return problem;
    }

    @ExceptionHandler(AgentDashboardController.MalformedAgentIdException.class)
    ProblemDetail malformedAgentId(AgentDashboardController.MalformedAgentIdException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Malformed agent id");
        return problem;
    }
}
