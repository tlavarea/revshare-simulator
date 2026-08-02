package com.revshare.domain.agent;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an agent.
 *
 * <p>A UUID rather than a database sequence, because identity has to be assignable before the write side has persisted
 * anything: a {@code TransactionClosed} event names an agent, and the read side must be able to resolve that name
 * without a round trip to the writer's database. Client-assigned identity is what lets the two services stay decoupled.
 */
public record AgentId(UUID value) implements Comparable<AgentId> {

    public AgentId {
        Objects.requireNonNull(value, "agent id must not be null");
    }

    public static AgentId of(UUID value) {
        return new AgentId(value);
    }

    public static AgentId fromString(String value) {
        return new AgentId(UUID.fromString(value));
    }

    public static AgentId newId() {
        return new AgentId(UUID.randomUUID());
    }

    @Override
    public int compareTo(AgentId other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
