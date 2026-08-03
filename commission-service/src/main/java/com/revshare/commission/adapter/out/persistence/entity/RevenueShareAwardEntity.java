package com.revshare.commission.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Persistence mapping for one revenue share award.
 *
 * <p>Append-only and immutable, like {@link CommissionSplitEntity}. Forfeited awards are stored too, with the reason
 * and a zero {@code awarded} amount, because "you were owed $312 and did not collect it, for this reason" cannot be
 * reconstructed after the fact.
 *
 * <p>Implements {@link Persistable} to avoid a wasted SELECT per row. Spring Data decides insert-versus-update by
 * asking whether the id is null; with a client-assigned id it never is, so {@code save} takes the {@code merge()} path
 * and issues a SELECT before every INSERT. One closing writes up to five awards, so that is five pointless round trips
 * on the hot path — exactly the kind of N+1 the batched standing lookups exist to avoid.
 */
@Entity
@Table(name = "revenue_share_award")
public class RevenueShareAwardEntity implements Persistable<UUID> {

    /**
     * True until this instance has been persisted or loaded. Not a column — it exists purely to answer {@link #isNew()}
     * without a database round trip.
     */
    @Transient
    private boolean isNew = true;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "beneficiary_id", nullable = false, updatable = false)
    private UUID beneficiaryId;

    @Column(name = "contributor_id", nullable = false, updatable = false)
    private UUID contributorId;

    @Column(name = "tier", nullable = false, updatable = false)
    private String tier;

    /**
     * The <em>contributor's</em> cap year. Annual tier maxima are drawn down per contributing agent, so the allowance
     * window belongs to whoever produced the closing, not to whoever is being paid.
     */
    @Column(name = "contributor_cap_year_start", nullable = false, updatable = false)
    private LocalDate contributorCapYearStart;

    @Column(name = "eligible_gross", nullable = false, updatable = false)
    private BigDecimal eligibleGross;

    @Column(name = "entitlement", nullable = false, updatable = false)
    private BigDecimal entitlement;

    @Column(name = "awarded", nullable = false, updatable = false)
    private BigDecimal awarded;

    @Column(name = "forfeited", nullable = false, updatable = false)
    private BigDecimal forfeited;

    @Column(name = "forfeit_reason", nullable = false, updatable = false)
    private String forfeitReason;

    @Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
    private Instant recordedAt;

    protected RevenueShareAwardEntity() {
        // Required by Hibernate.
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public RevenueShareAwardEntity(
            UUID id,
            UUID transactionId,
            UUID beneficiaryId,
            UUID contributorId,
            String tier,
            LocalDate contributorCapYearStart,
            BigDecimal eligibleGross,
            BigDecimal entitlement,
            BigDecimal awarded,
            BigDecimal forfeited,
            String forfeitReason) {
        this.id = id;
        this.transactionId = transactionId;
        this.beneficiaryId = beneficiaryId;
        this.contributorId = contributorId;
        this.tier = tier;
        this.contributorCapYearStart = contributorCapYearStart;
        this.eligibleGross = eligibleGross;
        this.entitlement = entitlement;
        this.awarded = awarded;
        this.forfeited = forfeited;
        this.forfeitReason = forfeitReason;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Flips the flag once the row exists, so a later save would correctly be an update. */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getBeneficiaryId() {
        return beneficiaryId;
    }

    public UUID getContributorId() {
        return contributorId;
    }

    public String getTier() {
        return tier;
    }

    public LocalDate getContributorCapYearStart() {
        return contributorCapYearStart;
    }

    public BigDecimal getEligibleGross() {
        return eligibleGross;
    }

    public BigDecimal getEntitlement() {
        return entitlement;
    }

    public BigDecimal getAwarded() {
        return awarded;
    }

    public BigDecimal getForfeited() {
        return forfeited;
    }

    public String getForfeitReason() {
        return forfeitReason;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
