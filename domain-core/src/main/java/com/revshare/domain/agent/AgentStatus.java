package com.revshare.domain.agent;

/**
 * Whether an agent is currently affiliated with the brokerage.
 *
 * <p>Note what this status does <em>not</em> control: an agent's position in anyone else's downline. A
 * {@link #TERMINATED} agent stops earning revenue share, but everyone above them keeps earning at their original tier,
 * and everyone below them stays at their original depth. See {@link SponsorshipPath}.
 */
public enum AgentStatus {

    /** Affiliated, may close transactions and may receive revenue share. */
    ACTIVE,

    /**
     * Has left the brokerage. Closes no new transactions and collects no revenue share, but remains a permanent
     * structural link in the sponsorship tree.
     */
    TERMINATED;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
