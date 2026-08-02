package com.revshare.domain.agent;

/**
 * Whether an agent has earned Elite status, which reduces the flat fee charged on post-cap transactions.
 *
 * <p>An enum rather than a boolean flag on {@link Agent}. The two values select different fee schedules in
 * {@code CommissionPlan}, and a named type makes the call sites read as {@code plan.postCapFee(ELITE)} instead of
 * {@code plan.postCapFee(true)}, where the meaning of {@code true} is anyone's guess.
 */
public enum EliteStatus {

    /** The default fee schedule. */
    STANDARD,

    /** A reduced post-cap transaction fee, earned through production above the cap. */
    ELITE
}
