package com.revshare.commission.adapter.out.persistence;

import com.revshare.commission.adapter.out.persistence.entity.AgentEntity;
import com.revshare.commission.adapter.out.persistence.entity.CapProgressEntity;
import com.revshare.commission.adapter.out.persistence.entity.CommissionSplitEntity;
import com.revshare.commission.adapter.out.persistence.entity.RevenueShareAwardEntity;
import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.AgentStatus;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.agent.EliteStatus;
import com.revshare.domain.agent.SponsorshipPath;
import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.revshare.ForfeitReason;
import com.revshare.domain.revshare.RevenueShareAward;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import com.revshare.domain.transaction.TransactionId;
import java.util.List;
import java.util.UUID;

/**
 * Translates between the domain model and the persistence model.
 *
 * <p>The whole cost of keeping JPA out of the core lives in this one class, and it is worth paying. Because the mapping
 * is explicit, {@code Agent} keeps its guarded transitions and has no setters, {@code Money} stays a value object
 * rather than a bare {@code BigDecimal}, and renaming a column is not a change to the domain.
 *
 * <p>Enums cross the boundary as their {@code name()}. Ordinals would be smaller but couple the stored data to
 * declaration order, so inserting a tier would silently relabel every historical row.
 */
final class PersistenceMapper {

    private PersistenceMapper() {}

    // --- agent -------------------------------------------------------------------------

    static Agent toDomain(AgentEntity entity) {
        return Agent.rehydrate(
                AgentId.of(entity.getId()),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getEmail(),
                entity.getJoinedOn(),
                new SponsorshipPath(
                        entity.getSponsorshipPath().stream().map(AgentId::of).toList()),
                AgentStatus.valueOf(entity.getStatus()),
                EliteStatus.valueOf(entity.getEliteStatus()),
                entity.getTerminatedOn());
    }

    static AgentEntity toEntity(Agent agent) {
        List<UUID> path = agent.sponsorshipPath().ancestorsNearestFirst().stream()
                .map(AgentId::value)
                .toList();
        return new AgentEntity(
                agent.id().value(),
                agent.firstName(),
                agent.lastName(),
                agent.email(),
                agent.joinedOn(),
                agent.sponsorId().map(AgentId::value).orElse(null),
                path,
                agent.status().name(),
                agent.eliteStatus().name(),
                agent.terminatedOn().orElse(null));
    }

    // --- cap progress ------------------------------------------------------------------

    static CapProgress toDomain(CapProgressEntity entity) {
        return new CapProgress(
                AgentId.of(entity.getAgentId()),
                new CapYear(entity.getCapYearStart(), entity.getCapYearEndExclusive(), entity.getCapYearOrdinal()),
                Money.of(entity.getContributed()),
                Money.of(entity.getCapAmount()));
    }

    static CapProgressEntity toEntity(CapProgress progress, UUID id) {
        return new CapProgressEntity(
                id,
                progress.agentId().value(),
                progress.capYear().start(),
                progress.capYear().endExclusive(),
                progress.capYear().ordinal(),
                progress.contributed().amount(),
                progress.capAmount().amount());
    }

    // --- commission split --------------------------------------------------------------

    static CommissionSplit toDomain(CommissionSplitEntity entity) {
        return new CommissionSplit(
                TransactionId.of(entity.getTransactionId()),
                AgentId.of(entity.getAgentId()),
                entity.getClosedOn(),
                Money.of(entity.getGrossCommissionIncome()),
                Money.of(entity.getAgentEarnings()),
                Money.of(entity.getCompanyEarnings()),
                Money.of(entity.getCapContribution()),
                Money.of(entity.getPostCapFeeCharged()),
                Money.of(entity.getRevenueShareEligibleGross()),
                entity.isPricedUnderPostCapFee(),
                entity.isReachedCapOnThisTransaction());
    }

    static CommissionSplitEntity toEntity(ClosedTransaction transaction, CommissionSplit split, CapYear capYear) {
        return new CommissionSplitEntity(
                split.transactionId().value(),
                split.agentId().value(),
                split.closedOn(),
                capYear.start(),
                transaction.salePrice().amount(),
                split.grossCommissionIncome().amount(),
                split.agentEarnings().amount(),
                split.companyEarnings().amount(),
                split.capContribution().amount(),
                split.postCapFeeCharged().amount(),
                split.revenueShareEligibleGross().amount(),
                split.pricedUnderPostCapFee(),
                split.reachedCapOnThisTransaction(),
                transaction.side().name(),
                transaction.propertyReference());
    }

    // --- revenue share award -----------------------------------------------------------

    static RevenueShareAward toDomain(RevenueShareAwardEntity entity) {
        return new RevenueShareAward(
                AgentId.of(entity.getBeneficiaryId()),
                AgentId.of(entity.getContributorId()),
                TransactionId.of(entity.getTransactionId()),
                RevenueShareTier.valueOf(entity.getTier()),
                Money.of(entity.getEligibleGross()),
                Money.of(entity.getEntitlement()),
                Money.of(entity.getAwarded()),
                Money.of(entity.getForfeited()),
                ForfeitReason.valueOf(entity.getForfeitReason()));
    }

    static RevenueShareAwardEntity toEntity(RevenueShareAward award, CapYear contributorCapYear, UUID id) {
        return new RevenueShareAwardEntity(
                id,
                award.transactionId().value(),
                award.beneficiary().value(),
                award.contributor().value(),
                award.tier().name(),
                contributorCapYear.start(),
                award.eligibleGross().amount(),
                award.entitlement().amount(),
                award.awarded().amount(),
                award.forfeited().amount(),
                award.forfeitReason().name());
    }
}
