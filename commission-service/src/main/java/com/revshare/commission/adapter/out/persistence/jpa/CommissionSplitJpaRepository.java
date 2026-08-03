package com.revshare.commission.adapter.out.persistence.jpa;

import com.revshare.commission.adapter.out.persistence.entity.CommissionSplitEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommissionSplitJpaRepository extends JpaRepository<CommissionSplitEntity, UUID> {

    /**
     * Trailing gross commission for one agent over a window.
     *
     * <p>{@code coalesce} matters: an agent who closed nothing in the window produces no rows, and a bare {@code sum}
     * would return null rather than zero. Returning null here would make the Producing Agent Policy throw instead of
     * correctly deciding that the agent is not producing.
     *
     * <p>Answered from {@code ix_commission_split_agent_closed_on}, which includes the amount column so the sum never
     * touches the heap.
     */
    @Query("""
            select coalesce(sum(s.grossCommissionIncome), 0)
            from CommissionSplitEntity s
            where s.agentId = :agentId
              and s.closedOn >= :fromInclusive
              and s.closedOn < :toExclusive
            """)
    BigDecimal sumGrossCommission(
            @Param("agentId") UUID agentId,
            @Param("fromInclusive") LocalDate fromInclusive,
            @Param("toExclusive") LocalDate toExclusive);

    /**
     * The same figure for many agents at once, as a single grouped aggregate.
     *
     * <p>This is the query that keeps the write path from degrading into an N+1. Evaluating one closing needs the
     * trailing production of the contributor's entire frontline to decide which revenue share tiers are unlocked, and a
     * prolific sponsor can have hundreds of them. Asked one at a time that is hundreds of round trips per closing.
     *
     * <p>Agents with no closings in the window are simply absent from the result; the adapter fills them in as zero
     * rather than having the database emit empty groups.
     */
    @Query("""
            select s.agentId as agentId, sum(s.grossCommissionIncome) as total
            from CommissionSplitEntity s
            where s.agentId in :agentIds
              and s.closedOn >= :fromInclusive
              and s.closedOn < :toExclusive
            group by s.agentId
            """)
    List<AgentGrossProjection> sumGrossCommissionByAgent(
            @Param("agentIds") Collection<UUID> agentIds,
            @Param("fromInclusive") LocalDate fromInclusive,
            @Param("toExclusive") LocalDate toExclusive);

    /**
     * Inserts a priced closing, unless that transaction has already been priced.
     *
     * <p>A native upsert rather than {@code JpaRepository.save}, for two independent reasons.
     *
     * <p>First, transaction semantics. A duplicate insert raises a constraint violation, and in Postgres that aborts
     * the whole transaction — so catching it and reading the existing row back cannot work, because the read is a
     * subsequent statement in a poisoned transaction. {@code ON CONFLICT DO NOTHING} never raises the error.
     *
     * <p>Second, and more insidious: this entity has an <em>assigned</em> primary key, so {@code save()} takes the
     * {@code merge()} path — a SELECT followed by an UPDATE when the row exists. A replayed event would therefore
     * silently overwrite the original pricing instead of being rejected, which is the exact opposite of what an
     * append-only, idempotent record is supposed to do.
     *
     * @return 1 if this call recorded the closing, 0 if it had already been priced
     */
    @Modifying
    @Query(value = """
                    INSERT INTO commission_split (
                        transaction_id, agent_id, closed_on, cap_year_start, sale_price,
                        gross_commission_income, agent_earnings, company_earnings,
                        cap_contribution, post_cap_fee_charged, revenue_share_eligible_gross,
                        priced_under_post_cap_fee, reached_cap_on_this_transaction,
                        side, property_reference)
                    VALUES (
                        :transactionId, :agentId, :closedOn, :capYearStart, :salePrice,
                        :grossCommissionIncome, :agentEarnings, :companyEarnings,
                        :capContribution, :postCapFeeCharged, :revenueShareEligibleGross,
                        :pricedUnderPostCapFee, :reachedCap,
                        :side, :propertyReference)
                    ON CONFLICT (transaction_id) DO NOTHING
                    """, nativeQuery = true)
    @SuppressWarnings("checkstyle:ParameterNumber")
    int insertIfAbsent(
            @Param("transactionId") UUID transactionId,
            @Param("agentId") UUID agentId,
            @Param("closedOn") LocalDate closedOn,
            @Param("capYearStart") LocalDate capYearStart,
            @Param("salePrice") BigDecimal salePrice,
            @Param("grossCommissionIncome") BigDecimal grossCommissionIncome,
            @Param("agentEarnings") BigDecimal agentEarnings,
            @Param("companyEarnings") BigDecimal companyEarnings,
            @Param("capContribution") BigDecimal capContribution,
            @Param("postCapFeeCharged") BigDecimal postCapFeeCharged,
            @Param("revenueShareEligibleGross") BigDecimal revenueShareEligibleGross,
            @Param("pricedUnderPostCapFee") boolean pricedUnderPostCapFee,
            @Param("reachedCap") boolean reachedCap,
            @Param("side") String side,
            @Param("propertyReference") String propertyReference);

    /** Projection for the grouped aggregate above. */
    interface AgentGrossProjection {
        UUID getAgentId();

        BigDecimal getTotal();
    }
}
