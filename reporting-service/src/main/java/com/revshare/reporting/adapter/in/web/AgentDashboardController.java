package com.revshare.reporting.adapter.in.web;

import com.revshare.domain.agent.AgentId;
import com.revshare.reporting.service.AgentDashboardQuery;
import com.revshare.reporting.service.AgentDashboardView;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read side's HTTP surface.
 *
 * <p>Read-only, and that is structural rather than a phase of the project. The write model is reached by publishing a
 * closing to {@code commission-service}; anything that mutated state here would be a second way into the system with no
 * cap arithmetic behind it, and the two would immediately disagree. A {@code POST} to this service should stay
 * impossible.
 *
 * <p>The controller does no mapping. {@link AgentDashboardQuery} returns the served shape already, so this class is
 * routing, id parsing and status codes — everything a driving adapter should be and nothing more.
 */
@RestController
@RequestMapping("/agents/{agentId}")
public class AgentDashboardController {

    private final AgentDashboardQuery dashboards;

    public AgentDashboardController(AgentDashboardQuery dashboards) {
        this.dashboards = dashboards;
    }

    /**
     * One agent's dashboard.
     *
     * <p>404 when the agent has no dashboard, which is not quite the same as "no such agent" — this service learns
     * about agents only from events, so an agent who has never closed anything and never earned revenue share is
     * indistinguishable here from one who does not exist. The read side has no roster to check against, and inventing
     * an empty dashboard to return 200 would be worse: it would assert that a real agent has zero production when the
     * truth is that nothing is known about them.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<AgentDashboardView> dashboard(@PathVariable String agentId) {
        AgentId id = parse(agentId);
        return dashboards
                .findByAgentId(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new DashboardNotFoundException(id));
    }

    /**
     * Parses the path variable, rejecting a malformed id as a 400 rather than letting it become a 404.
     *
     * <p>The distinction is worth the four lines: "you asked wrongly" and "there is nothing here" send a caller looking
     * in different places, and {@code UUID.fromString} on a path variable would otherwise surface as a 500.
     */
    private static AgentId parse(String agentId) {
        try {
            return AgentId.of(UUID.fromString(agentId));
        } catch (IllegalArgumentException e) {
            throw new MalformedAgentIdException(agentId);
        }
    }

    /** No dashboard has been projected for this agent. */
    public static class DashboardNotFoundException extends RuntimeException {
        public DashboardNotFoundException(AgentId agentId) {
            super("no dashboard has been projected for agent " + agentId);
        }
    }

    /** The path variable is not a UUID. */
    public static class MalformedAgentIdException extends RuntimeException {
        public MalformedAgentIdException(String value) {
            super("'" + value + "' is not a valid agent id");
        }
    }
}
