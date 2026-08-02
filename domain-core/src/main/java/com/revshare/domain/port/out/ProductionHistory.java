package com.revshare.domain.port.out;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * Driven port for querying what agents have produced over a period.
 *
 * <p>Feeds the two time-dependent eligibility questions in the revenue share program: does this beneficiary still meet
 * the Producing Agent Policy, and how many of their frontline agents do. Both are historical aggregates rather than
 * current state, which is why they are their own port and not a method on {@link AgentRepository}.
 *
 * <p>Every method takes an explicit window rather than reading a clock. Reprocessing a six-month-old closing must ask
 * what was true then.
 */
public interface ProductionHistory {

    /** Gross commission from closings in {@code [fromInclusive, toExclusive)}. */
    Money grossCommissionBetween(AgentId agentId, LocalDate fromInclusive, LocalDate toExclusive);

    /**
     * The same figure for many agents at once.
     *
     * <p>Batched because evaluating one closing needs the trailing production of the contributor's whole frontline,
     * which can be hundreds of agents. Issuing that as hundreds of round trips is the obvious way to make this system
     * slow, and the adapter can answer it with a single grouped aggregate instead.
     */
    Map<AgentId, Money> grossCommissionBetween(
            Collection<AgentId> agentIds, LocalDate fromInclusive, LocalDate toExclusive);
}
