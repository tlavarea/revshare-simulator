package com.revshare.commission.adapter.out.persistence;

import com.revshare.commission.adapter.out.persistence.jpa.CommissionSplitJpaRepository;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.port.out.ProductionHistory;
import com.revshare.domain.shared.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link ProductionHistory}.
 *
 * <p>Reads the same table {@link CommissionSplitRepositoryAdapter} writes, but exists as a separate adapter because the
 * questions are different in kind: one records what happened to a closing, the other asks aggregate questions about a
 * window of them. The indices they rely on differ too.
 */
@Component
public class ProductionHistoryAdapter implements ProductionHistory {

    private final CommissionSplitJpaRepository splits;

    public ProductionHistoryAdapter(CommissionSplitJpaRepository splits) {
        this.splits = splits;
    }

    @Override
    @Transactional(readOnly = true)
    public Money grossCommissionBetween(AgentId agentId, LocalDate fromInclusive, LocalDate toExclusive) {
        return Money.of(splits.sumGrossCommission(agentId.value(), fromInclusive, toExclusive));
    }

    /**
     * The batched form, and the reason the single-agent version is rarely the right call.
     *
     * <p>Deciding which revenue share tiers a beneficiary has unlocked means counting how many of their frontline
     * agents are currently producing, and a prolific sponsor can have hundreds. Asking one agent at a time turns a
     * single grouped aggregate into hundreds of round trips, per closing, per beneficiary.
     *
     * <p>Agents with no closings in the window produce no group, so they are seeded to zero first. Returning a map with
     * holes in it would push that same defaulting onto every caller, and the one that forgot would read "absent" as
     * "not yet checked".
     */
    @Override
    @Transactional(readOnly = true)
    public Map<AgentId, Money> grossCommissionBetween(
            Collection<AgentId> agentIds, LocalDate fromInclusive, LocalDate toExclusive) {

        if (agentIds.isEmpty()) {
            return Map.of();
        }

        Map<AgentId, Money> totals = new LinkedHashMap<>();
        agentIds.forEach(id -> totals.put(id, Money.ZERO));

        splits.sumGrossCommissionByAgent(agentIds.stream().map(AgentId::value).toList(), fromInclusive, toExclusive)
                .forEach(row -> totals.put(AgentId.of(row.getAgentId()), Money.of(row.getTotal())));

        return totals;
    }
}
