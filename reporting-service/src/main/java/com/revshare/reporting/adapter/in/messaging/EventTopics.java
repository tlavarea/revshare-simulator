package com.revshare.reporting.adapter.in.messaging;

/**
 * The topics this service consumes.
 *
 * <p>The same three names the write side's relay publishes to, and deliberately <em>restated</em> here rather than
 * shared from a common module. Topic names are the wire contract between two independently deployable services; a
 * shared constant would make them look like an implementation detail one side could rename in a refactor. Written out
 * on both sides, changing one without the other is a broken deployment rather than a silent rename — which is what a
 * contract should feel like.
 */
final class EventTopics {

    static final String COMMISSION = "revshare.commission.events";
    static final String REVENUE_SHARE = "revshare.revenue-share.events";
    static final String TRANSACTION = "revshare.transaction.events";

    private EventTopics() {}
}
