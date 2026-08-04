package com.revshare.commission.adapter.in.web;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.port.in.AgentAffiliation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An enrolment, as a client sends it.
 *
 * <p>{@code sponsorId} is optional and its absence is meaningful rather than incomplete: the agents at the top of each
 * tree have no sponsor, permanently. Note what the request does <em>not</em> carry — a sponsorship path. That is
 * derived from the sponsor's own path by {@link AgentAffiliation}, and accepting one here would let a client assert
 * their position in the hierarchy, which every tier and every revenue share rate downstream depends on being computed.
 *
 * <p>The agent id is the client's to assign, for the same reason {@code AgentId} is a UUID at all: identity has to
 * exist before the write side has persisted anything, so a caller enrolling a sponsor and then their downline in two
 * requests already knows the id to point the second one at.
 */
public record EnrollAgentRequest(
        @NotNull(message = "agentId is required") UUID agentId,
        @NotBlank(message = "firstName is required") String firstName,
        @NotBlank(message = "lastName is required") String lastName,

        @NotBlank(message = "email is required") @Email(message = "email must be a valid address")
        String email,

        @NotNull(message = "joinedOn is required") LocalDate joinedOn,
        UUID sponsorId) {

    /**
     * Builds the domain input, translating its rejection into a 400.
     *
     * <p>Wrapped at the one place it can legitimately arise rather than mapping {@code IllegalArgumentException}
     * globally, which would also convert internal bugs into tidy client errors.
     */
    public AgentAffiliation.Enrollment toEnrollment() {
        try {
            return new AgentAffiliation.Enrollment(
                    AgentId.of(agentId),
                    firstName,
                    lastName,
                    email,
                    joinedOn,
                    sponsorId == null ? null : AgentId.of(sponsorId));
        } catch (IllegalArgumentException e) {
            throw new MalformedEnrollmentException(e.getMessage(), e);
        }
    }

    /** The request parsed, but describes an enrolment the domain will not accept. */
    public static class MalformedEnrollmentException extends RuntimeException {
        public MalformedEnrollmentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
