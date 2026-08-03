package com.revshare.commission.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence mapping for a priced closing.
 *
 * <p>No {@code @Version} field, and no setters. This row is written once and never updated, so there is no concurrent
 * modification to lose and nothing to lock: the primary key is the client-assigned transaction id, which makes a
 * duplicate insert fail loudly instead of overwriting the original pricing.
 */
@Entity
@Table(name = "commission_split")
public class CommissionSplitEntity {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "closed_on", nullable = false, updatable = false)
    private LocalDate closedOn;

    /** Which cap year this closing was priced against, as resolved at pricing time. */
    @Column(name = "cap_year_start", nullable = false, updatable = false)
    private LocalDate capYearStart;

    @Column(name = "sale_price", nullable = false, updatable = false)
    private BigDecimal salePrice;

    @Column(name = "gross_commission_income", nullable = false, updatable = false)
    private BigDecimal grossCommissionIncome;

    @Column(name = "agent_earnings", nullable = false, updatable = false)
    private BigDecimal agentEarnings;

    @Column(name = "company_earnings", nullable = false, updatable = false)
    private BigDecimal companyEarnings;

    @Column(name = "cap_contribution", nullable = false, updatable = false)
    private BigDecimal capContribution;

    @Column(name = "post_cap_fee_charged", nullable = false, updatable = false)
    private BigDecimal postCapFeeCharged;

    @Column(name = "revenue_share_eligible_gross", nullable = false, updatable = false)
    private BigDecimal revenueShareEligibleGross;

    @Column(name = "priced_under_post_cap_fee", nullable = false, updatable = false)
    private boolean pricedUnderPostCapFee;

    @Column(name = "reached_cap_on_this_transaction", nullable = false, updatable = false)
    private boolean reachedCapOnThisTransaction;

    @Column(name = "side", nullable = false, updatable = false)
    private String side;

    @Column(name = "property_reference", nullable = false, updatable = false)
    private String propertyReference;

    @Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
    private Instant recordedAt;

    protected CommissionSplitEntity() {
        // Required by Hibernate.
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CommissionSplitEntity(
            UUID transactionId,
            UUID agentId,
            LocalDate closedOn,
            LocalDate capYearStart,
            BigDecimal salePrice,
            BigDecimal grossCommissionIncome,
            BigDecimal agentEarnings,
            BigDecimal companyEarnings,
            BigDecimal capContribution,
            BigDecimal postCapFeeCharged,
            BigDecimal revenueShareEligibleGross,
            boolean pricedUnderPostCapFee,
            boolean reachedCapOnThisTransaction,
            String side,
            String propertyReference) {
        this.transactionId = transactionId;
        this.agentId = agentId;
        this.closedOn = closedOn;
        this.capYearStart = capYearStart;
        this.salePrice = salePrice;
        this.grossCommissionIncome = grossCommissionIncome;
        this.agentEarnings = agentEarnings;
        this.companyEarnings = companyEarnings;
        this.capContribution = capContribution;
        this.postCapFeeCharged = postCapFeeCharged;
        this.revenueShareEligibleGross = revenueShareEligibleGross;
        this.pricedUnderPostCapFee = pricedUnderPostCapFee;
        this.reachedCapOnThisTransaction = reachedCapOnThisTransaction;
        this.side = side;
        this.propertyReference = propertyReference;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public LocalDate getClosedOn() {
        return closedOn;
    }

    public LocalDate getCapYearStart() {
        return capYearStart;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public BigDecimal getGrossCommissionIncome() {
        return grossCommissionIncome;
    }

    public BigDecimal getAgentEarnings() {
        return agentEarnings;
    }

    public BigDecimal getCompanyEarnings() {
        return companyEarnings;
    }

    public BigDecimal getCapContribution() {
        return capContribution;
    }

    public BigDecimal getPostCapFeeCharged() {
        return postCapFeeCharged;
    }

    public BigDecimal getRevenueShareEligibleGross() {
        return revenueShareEligibleGross;
    }

    public boolean isPricedUnderPostCapFee() {
        return pricedUnderPostCapFee;
    }

    public boolean isReachedCapOnThisTransaction() {
        return reachedCapOnThisTransaction;
    }

    public String getSide() {
        return side;
    }

    public String getPropertyReference() {
        return propertyReference;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
