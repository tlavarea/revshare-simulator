package com.revshare.domain.port.in;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Driving port: begin and end an agent's affiliation with the brokerage.
 *
 * <p>The second way into the write side, alongside {@link RecordClosedTransaction}. Both operations live on one port
 * because they are the same aggregate's lifecycle and share a transaction shape — save the agent, announce the fact —
 * and splitting them would produce two interfaces over one service with nothing between them.
 *
 * <p>Enrolment is where the sponsorship tree is decided, and it is the only place it can be. {@code SponsorshipPath} is
 * computed once from the sponsor's own path and then frozen, so an implementation must load the sponsor rather than
 * accept a path from the caller; a client-supplied path would let the hierarchy be asserted rather than derived, and
 * every tier calculation downstream depends on it being derived.
 *
 * <p>Declared in the core rather than in the service module, so the dependency still points inward: a driving adapter
 * depends on this, and this depends on nothing.
 */
public interface AgentAffiliation {

    /**
     * Enrols an agent, beneath a sponsor or at the top of a tree.
     *
     * <p><strong>Not idempotent, deliberately.</strong> Unlike recording a closing, re-sending an enrolment is not a
     * harmless replay: the request carries a name and an email that may differ from the stored ones, and silently
     * accepting it would either overwrite an agent's record from what looks like a retry, or silently discard the new
     * values while reporting success. Neither is defensible for a record that fixes both the cap anniversary and a
     * position in the tree, so a second enrolment of the same id is rejected and the caller decides what they meant.
     *
     * @throws AgentAlreadyEnrolledException if the id is already enrolled
     * @throws UnknownSponsorException if a sponsor is named but not enrolled
     */
    Agent enroll(Enrollment enrollment);

    /**
     * Ends an agent's affiliation.
     *
     * <p>Stops them collecting revenue share and nothing else. The tree does not compress, their downline does not move
     * up, and their upline keeps every tier it had — see {@code SponsorshipPath}.
     *
     * @throws UnknownAgentException if no such agent is enrolled
     * @throws IllegalStateException if the agent has already been terminated
     */
    Agent terminate(AgentId agentId, LocalDate on);

    /**
     * Everything needed to enrol one agent.
     *
     * <p>{@code sponsorId} is optional rather than overloaded into two methods: "enrolled by nobody" is a real and
     * permanent state — the agents at the top of each tree — not a missing value to be filled in later.
     */
    record Enrollment(
            AgentId id, String firstName, String lastName, String email, LocalDate joinedOn, AgentId sponsorId) {

        public Enrollment {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(firstName, "firstName must not be null");
            Objects.requireNonNull(lastName, "lastName must not be null");
            Objects.requireNonNull(email, "email must not be null");
            Objects.requireNonNull(joinedOn, "joinedOn must not be null");

            if (id.equals(sponsorId)) {
                throw new IllegalArgumentException("an agent cannot sponsor themselves: " + id);
            }
        }

        /** An agent at the top of their own tree. */
        public static Enrollment unsponsored(
                AgentId id, String firstName, String lastName, String email, LocalDate joinedOn) {
            return new Enrollment(id, firstName, lastName, email, joinedOn, null);
        }

        public Optional<AgentId> sponsor() {
            return Optional.ofNullable(sponsorId);
        }
    }

    /** The id is already enrolled. */
    class AgentAlreadyEnrolledException extends RuntimeException {
        public AgentAlreadyEnrolledException(AgentId agentId) {
            super("agent " + agentId + " is already enrolled");
        }
    }

    /** The enrolment names a sponsor this brokerage has never enrolled. */
    class UnknownSponsorException extends RuntimeException {
        public UnknownSponsorException(AgentId sponsorId) {
            super("no agent found with id " + sponsorId + " to sponsor this enrolment");
        }
    }

    /** The operation names an agent that does not exist. */
    class UnknownAgentException extends RuntimeException {
        public UnknownAgentException(AgentId agentId) {
            super("no agent found with id " + agentId);
        }
    }
}
