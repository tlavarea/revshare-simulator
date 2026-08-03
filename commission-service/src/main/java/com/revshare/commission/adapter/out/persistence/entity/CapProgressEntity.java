package com.revshare.commission.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence mapping for one agent's progress toward the cap in one cap year.
 *
 * <p>The {@link Version} field is the point of this entity. Cap progress is the only read-modify-write aggregate on the
 * write path, and it is the one place where losing a concurrent update costs real money: two closings priced in
 * parallel for one agent would both read the same balance, both compute a full 15% share, and both write — leaving the
 * agent under-contributed and free to earn past their cap, with nothing in the data afterwards to show it happened.
 *
 * <p>Optimistic locking turns that silent corruption into an {@code OptimisticLockingFailureException} the caller can
 * retry. Pessimistic locking would also work, but this aggregate is contended only when one agent closes two deals at
 * the same instant, so taking a row lock on every closing would cost more than it saves.
 */
@Entity
@Table(name = "cap_progress")
public class CapProgressEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "cap_year_start", nullable = false, updatable = false)
    private LocalDate capYearStart;

    @Column(name = "cap_year_end_exclusive", nullable = false, updatable = false)
    private LocalDate capYearEndExclusive;

    @Column(name = "cap_year_ordinal", nullable = false, updatable = false)
    private int capYearOrdinal;

    @Column(name = "contributed", nullable = false)
    private BigDecimal contributed;

    /**
     * Stored per row rather than read from configuration. The cap in force when this year opened is a property of the
     * year, and a later change to the plan must not silently move the goalposts on an agent halfway through theirs.
     */
    @Column(name = "cap_amount", nullable = false, updatable = false)
    private BigDecimal capAmount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CapProgressEntity() {
        // Required by Hibernate.
    }

    public CapProgressEntity(
            UUID id,
            UUID agentId,
            LocalDate capYearStart,
            LocalDate capYearEndExclusive,
            int capYearOrdinal,
            BigDecimal contributed,
            BigDecimal capAmount) {
        this.id = id;
        this.agentId = agentId;
        this.capYearStart = capYearStart;
        this.capYearEndExclusive = capYearEndExclusive;
        this.capYearOrdinal = capYearOrdinal;
        this.contributed = contributed;
        this.capAmount = capAmount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public LocalDate getCapYearStart() {
        return capYearStart;
    }

    public LocalDate getCapYearEndExclusive() {
        return capYearEndExclusive;
    }

    public int getCapYearOrdinal() {
        return capYearOrdinal;
    }

    public BigDecimal getContributed() {
        return contributed;
    }

    public BigDecimal getCapAmount() {
        return capAmount;
    }

    public long getVersion() {
        return version;
    }

    /** The only mutable field. Everything else about a cap year is fixed when it opens. */
    public void setContributed(BigDecimal contributed) {
        this.contributed = contributed;
    }
}
