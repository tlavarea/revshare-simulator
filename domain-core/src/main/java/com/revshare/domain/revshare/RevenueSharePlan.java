package com.revshare.domain.revshare;

import com.revshare.domain.commission.CommissionPlan;
import com.revshare.domain.shared.Money;
import com.revshare.domain.shared.Percentage;
import java.util.Objects;

/**
 * The revenue share schedule, bound to the commission plan that funds it.
 *
 * <p>The two are inseparable, and holding them together is what lets the published per-tier annual maxima be
 * <em>derived</em> rather than hard-coded. The chain is:
 *
 * <pre>
 *   an agent caps at                    $12,000 of company dollar
 *   which at a 15% split requires       $12,000 / 0.15 = $80,000 of gross commission
 *   so tier 1, paying 5% of gross,      $80,000 x 5%   = $4,000 per capping agent per year
 *   tier 2, paying 4%,                  $80,000 x 4%   = $3,200
 *   tier 3, 3%                                         = $2,400
 *   tier 4, 2%                                         = $1,600
 *   tier 5, 1%                                         =   $800
 * </pre>
 *
 * <p>Those five figures are exactly the published annual caps. They are not independent numbers that happen to appear
 * in a brochure, they are what falls out of the split, the cap, and the tier rates. Deriving them means the schedule
 * cannot drift out of internal consistency: change the cap to $14,000 and every tier maximum moves correctly on its
 * own.
 *
 * <p>A per-tier maximum applies <em>per contributing agent</em>, not in aggregate. A beneficiary with thirty capping
 * frontline agents earns up to $4,000 from each of them.
 */
public record RevenueSharePlan(CommissionPlan commissionPlan) {

    public RevenueSharePlan {
        Objects.requireNonNull(commissionPlan, "commissionPlan must not be null");

        // Revenue share is paid out of the company's share, never on top of it. If the tier
        // rates summed to more than the split, a fully unlocked upline would be owed money
        // the company never collected.
        Percentage totalPayout = RevenueShareTier.totalPayoutRate();
        if (totalPayout.compareTo(commissionPlan.companySplit()) > 0) {
            throw new IllegalArgumentException("revenue share tiers pay out " + totalPayout
                    + " but the company only collects " + commissionPlan.companySplit()
                    + " of gross commission");
        }
    }

    /** The standard 85/15 commission plan and the five-tier program it funds. */
    public static RevenueSharePlan standard() {
        return new RevenueSharePlan(CommissionPlan.standard());
    }

    /**
     * The most a beneficiary can earn at one tier, from one contributing agent, in one of that contributor's cap years.
     *
     * <p>Derived from the gross commission required to cap, because that is the ceiling on how much of any single
     * agent's production can generate company dollar in a year, and revenue share stops the moment that agent caps.
     */
    public Money annualMaximumPerContributor(RevenueShareTier tier) {
        Objects.requireNonNull(tier, "tier must not be null");
        return commissionPlan.grossCommissionRequiredToCap().multipliedBy(tier.rate());
    }

    /**
     * The most the entire five-tier upline can draw from one contributor in a cap year, which equals the commission cap
     * itself when every tier is unlocked and earning.
     */
    public Money annualMaximumAcrossAllTiers() {
        Money total = Money.ZERO;
        for (RevenueShareTier tier : RevenueShareTier.values()) {
            total = total.plus(annualMaximumPerContributor(tier));
        }
        return total;
    }
}
