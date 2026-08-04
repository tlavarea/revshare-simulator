package com.revshare.commission.adapter.in.web;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.port.in.AgentAffiliation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent lifecycle: joining the brokerage, and leaving it.
 *
 * <p>The second driving adapter on the write side. It exists because the sponsorship tree has to be built somewhere,
 * and until now it could only be built by calling {@code AgentRepository.save} from inside the JVM — which announced
 * nothing, so the read side never learned that an agent existed until money moved on their behalf.
 *
 * <h2>Termination is a sub-resource, not a DELETE</h2>
 *
 * <p>{@code POST /agents/{id}/termination} rather than {@code DELETE /agents/{id}}, because nothing is deleted. The
 * agent record persists in full, their downline keeps its place beneath them, and their upline keeps earning through
 * them; what ends is their own ability to collect. A {@code DELETE} would say the opposite of all three, and the first
 * reader to believe it would go looking for a tree that had compressed.
 *
 * <p>It also takes a date. Termination is a dated fact, not "now" — a brokerage records a departure after the paperwork
 * clears, and revenue share eligibility is evaluated against the date the affiliation actually ended.
 */
@RestController
@RequestMapping("/agents")
public class AgentController {

    private final AgentAffiliation affiliation;

    public AgentController(AgentAffiliation affiliation) {
        this.affiliation = affiliation;
    }

    /**
     * Enrols an agent.
     *
     * <p>201 with the agent as the brokerage now holds them, including the sponsorship path derived from their
     * sponsor's. 409 for an id already enrolled — see {@link AgentAffiliation#enroll} for why a repeat enrolment is a
     * conflict rather than an idempotent replay. 404 when a named sponsor does not exist, because the request is
     * well-formed and what is missing is an agent.
     */
    @PostMapping
    public ResponseEntity<AgentView> enroll(@Valid @RequestBody EnrollAgentRequest request) {
        Agent enrolled = affiliation.enroll(request.toEnrollment());
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentView.from(enrolled));
    }

    /**
     * Ends an agent's affiliation, as of a date.
     *
     * <p>200 rather than 201: the termination is a state change to an agent that already exists, and there is no new
     * resource to point at. Terminating an already-terminated agent is a 409 — the aggregate refuses it, and silently
     * accepting would let a second, later date overwrite the real departure.
     */
    @PostMapping("/{agentId}/termination")
    public ResponseEntity<AgentView> terminate(
            @PathVariable String agentId, @Valid @RequestBody TerminateAgentRequest request) {
        Agent terminated = affiliation.terminate(parse(agentId), request.terminatedOn());
        return ResponseEntity.ok(AgentView.from(terminated));
    }

    /**
     * Parses the path variable, rejecting a malformed id as a 400 rather than letting it become a 404.
     *
     * <p>Same reasoning as on the read side: "you asked wrongly" and "there is nothing here" send a caller looking in
     * different places, and {@code UUID.fromString} on a path variable would otherwise surface as a 500.
     */
    private static AgentId parse(String agentId) {
        try {
            return AgentId.of(UUID.fromString(agentId));
        } catch (IllegalArgumentException e) {
            throw new MalformedAgentIdException(agentId);
        }
    }

    /** When the affiliation ended. A body rather than a query parameter, because it is the state being submitted. */
    public record TerminateAgentRequest(
            @NotNull(message = "terminatedOn is required") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate terminatedOn) {}

    /** The path variable is not a UUID. */
    public static class MalformedAgentIdException extends RuntimeException {
        public MalformedAgentIdException(String value) {
            super("'" + value + "' is not a valid agent id");
        }
    }
}
