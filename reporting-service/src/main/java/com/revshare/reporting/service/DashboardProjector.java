package com.revshare.reporting.service;

import com.revshare.domain.event.DomainEvent;

/**
 * Folds one domain event into the read model.
 *
 * <p>The read side's one use case, and the only thing that writes to Mongo. Declared as an interface for the same
 * reason the write side's services are: it is the seam the messaging adapter depends on, so a consumer test can drive
 * projection without a broker and a projection test can run without a consumer.
 *
 * <p><strong>Idempotent on event id.</strong> Applying the same event twice is a no-op. Callers consuming an
 * at-least-once stream depend on this and should not attempt their own deduplication.
 */
public interface DashboardProjector {

    /**
     * Applies the event, or does nothing if it has already been applied.
     *
     * @return true if the event was applied, false if it was recognised as already projected
     */
    boolean apply(DomainEvent event);
}
