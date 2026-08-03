package com.revshare.commission;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.port.out.AgentRepository;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import com.revshare.domain.transaction.TransactionId;
import com.revshare.domain.transaction.TransactionSide;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds small, precisely-shaped brokerages for integration tests.
 *
 * <p>Hand-built rather than generated. {@code seed-generator} produces realistic data, which is the right input for a
 * fitness check but the wrong input for asserting that an agent five levels down pays exactly $400 to their tier 2
 * sponsor — for that, every figure has to be chosen, not drawn from a lognormal.
 */
public final class TestBrokerage {

    /** Everyone joins here, so cap years are predictable and all start on the same day. */
    public static final LocalDate JOINED = LocalDate.of(2024, 1, 15);

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AgentRepository agents;

    public TestBrokerage(AgentRepository agents) {
        this.agents = agents;
    }

    /** A single agent at the top of their own tree. */
    public Agent founder() {
        int n = SEQUENCE.incrementAndGet();
        Agent agent = Agent.enroll(AgentId.newId(), "Founder", "Number" + n, "founder" + n + "@example.test", JOINED);
        agents.save(agent);
        return agent;
    }

    /**
     * A straight chain of {@code length} agents, index 0 at the top.
     *
     * <p>The last agent is the contributor in most tests; everyone above them is a beneficiary at the tier matching
     * their distance.
     */
    public List<Agent> chain(int length) {
        List<Agent> built = new ArrayList<>(length);
        Agent previous = founder();
        built.add(previous);
        for (int i = 1; i < length; i++) {
            built.add(previous = sponsoredBy(previous));
        }
        return built;
    }

    /** Enrols a new agent directly beneath {@code sponsor}. */
    public Agent sponsoredBy(Agent sponsor) {
        int n = SEQUENCE.incrementAndGet();
        Agent agent = Agent.enrollSponsoredBy(
                AgentId.newId(),
                "Agent",
                "Number" + n,
                "agent" + n + "@example.test",
                JOINED,
                sponsor.id(),
                sponsor.sponsorshipPath());
        agents.save(agent);
        return agent;
    }

    /** Marks an agent as having left, and persists the change. */
    public void terminate(Agent agent, LocalDate on) {
        agent.terminate(on);
        agents.save(agent);
    }

    /**
     * A closing with an exact gross commission.
     *
     * <p>The sale price is nominal — nothing in the commission calculation derives from it, because gross commission is
     * negotiated per deal rather than recovered from a standard rate.
     */
    public static ClosedTransaction closing(Agent agent, String grossCommission, LocalDate closedOn) {
        return new ClosedTransaction(
                TransactionId.newId(),
                agent.id(),
                closedOn,
                Money.of("1000000.00"),
                Money.of(grossCommission),
                TransactionSide.LISTING,
                "PROP-" + SEQUENCE.incrementAndGet());
    }

    public static ClosedTransaction closing(Agent agent, String grossCommission) {
        return closing(agent, grossCommission, JOINED.plusMonths(3));
    }
}
