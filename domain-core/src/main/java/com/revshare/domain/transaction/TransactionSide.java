package com.revshare.domain.transaction;

/**
 * Which side of the deal the agent represented.
 *
 * <p>Does not affect the commission split arithmetic, which operates on gross commission income regardless of origin.
 * It is modeled because it is the dimension brokerage dashboards slice production by, and because {@link #DUAL} is the
 * case where a single closing yields roughly double the usual gross commission, which materially changes how quickly an
 * agent reaches the cap.
 */
public enum TransactionSide {

    /** The agent represented the seller. */
    LISTING,

    /** The agent represented the buyer. */
    BUYING,

    /** The agent represented both parties and collected both sides of the commission. */
    DUAL;

    /** How many commission sides this closing produces. Used by the seed generator. */
    public int commissionSides() {
        return this == DUAL ? 2 : 1;
    }
}
