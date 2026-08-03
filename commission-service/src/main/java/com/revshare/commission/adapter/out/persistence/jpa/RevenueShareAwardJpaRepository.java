package com.revshare.commission.adapter.out.persistence.jpa;

import com.revshare.commission.adapter.out.persistence.entity.RevenueShareAwardEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RevenueShareAwardJpaRepository extends JpaRepository<RevenueShareAwardEntity, UUID> {

    /**
     * How much of a tier's annual maximum a beneficiary has already drawn from one contributor.
     *
     * <p>Consulted up to five times per closing, once per eligible ancestor, so it is the busiest read in the service.
     * {@code ix_award_allowance_lookup} covers all four predicates in order and includes {@code awarded}, making it an
     * index-only scan.
     *
     * <p>Scoped to a single contributor on purpose. The annual maximum is per contributing agent, not per beneficiary —
     * summing across contributors here would cap an agent with a fifty-strong downline at one agent's worth of
     * earnings.
     */
    @Query("""
            select coalesce(sum(a.awarded), 0)
            from RevenueShareAwardEntity a
            where a.beneficiaryId = :beneficiaryId
              and a.contributorId = :contributorId
              and a.tier = :tier
              and a.contributorCapYearStart = :capYearStart
            """)
    BigDecimal sumAwarded(
            @Param("beneficiaryId") UUID beneficiaryId,
            @Param("contributorId") UUID contributorId,
            @Param("tier") String tier,
            @Param("capYearStart") LocalDate capYearStart);

    List<RevenueShareAwardEntity> findAllByTransactionId(UUID transactionId);

    boolean existsByTransactionId(UUID transactionId);
}
