package com.revshare.domain.revshare;

/**
 * Why a revenue share entitlement was not paid in full.
 *
 * <p>Recorded on every award, including the ones paid in full, so the read side can show an agent what they earned
 * <em>and</em> what they left on the table. "You forfeited $312 last quarter because your trailing production fell
 * below the threshold" is the single most actionable thing a revenue share dashboard can say, and it is
 * unreconstructable after the fact unless the reason is captured at distribution time.
 */
public enum ForfeitReason {

    /** Paid in full. */
    NONE,

    /** The beneficiary had left the brokerage when the contributing transaction closed. */
    BENEFICIARY_NOT_AFFILIATED,

    /**
     * The beneficiary had too few producing frontline agents to have unlocked this tier. See
     * {@link RevenueShareTier#producingFrontlineRequired()}.
     */
    TIER_LOCKED,

    /**
     * The beneficiary failed the Producing Agent Policy: their own trailing production was below the threshold. See
     * {@link ProducingAgentPolicy}.
     */
    BENEFICIARY_NOT_PRODUCING,

    /**
     * The beneficiary had already drawn this tier's annual maximum from this particular contributor. Partial payment is
     * possible here, unlike the other reasons: the award is paid up to the remaining allowance and only the excess is
     * forfeited.
     */
    ANNUAL_TIER_MAXIMUM_REACHED;

    public boolean isForfeiture() {
        return this != NONE;
    }
}
