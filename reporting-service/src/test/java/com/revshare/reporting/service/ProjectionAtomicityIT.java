package com.revshare.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.event.RevenueShareDistributed;
import com.revshare.reporting.AbstractMongoIT;
import com.revshare.reporting.TestEvents;
import com.revshare.reporting.adapter.out.mongo.AgentDashboardMongoRepository;
import com.revshare.reporting.adapter.out.mongo.document.AgentDashboardDocument;
import com.revshare.reporting.adapter.out.mongo.document.ProcessedEventDocument;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proves the projector's transaction is real.
 *
 * <p>Every other test in this module passes just as well with no transaction manager at all — they apply events one at
 * a time and never fail part-way, so nothing forces the atomicity to be exercised. That is precisely the danger with a
 * Mongo {@code @Transactional}: Spring Boot does not auto-configure a
 * {@link org.springframework.data.mongodb.MongoTransactionManager}, and without one the annotation is silently ignored.
 * The projector would look correct and would double-count on every redelivery that arrived after a partial write.
 *
 * <p>So this test injects a failure half-way through a fan-out and asserts that <em>nothing</em> survives it: not the
 * dashboards already written, and not the processed-event marker. Both assertions were watched failing with the
 * transaction manager bean commented out, and they fail in the two distinct ways the design predicts: the partial
 * dashboards remain, and the retry is then waved through as a duplicate, so the distribution is lost for good.
 *
 * <h2>The one place a mock belongs</h2>
 *
 * <p>There are no test doubles anywhere in {@code domain-core} and none in the rest of this module — the projections
 * are tested against a real database because a fake would not have {@code Decimal128} or transactions to prove anything
 * about. Here the thing under test <em>is</em> the failure path, and a mid-transaction infrastructure failure is not
 * something a real Mongo can be asked for on cue. A spy is the only way to ask.
 */
@DisplayName("projection atomicity")
class ProjectionAtomicityIT extends AbstractMongoIT {

    @Autowired
    private DashboardProjector projector;

    @MockitoSpyBean
    private AgentDashboardMongoRepository dashboards;

    @Test
    void aFailurePartWayThroughAFanOutLeavesNothingBehind() {
        List<AgentId> upline = List.of(TestEvents.agent(), TestEvents.agent(), TestEvents.agent());
        RevenueShareDistributed event =
                TestEvents.upline(upline, TestEvents.agent(), TestEvents.transaction(), "80000.00");

        // Let the first two beneficiaries project, then fail on the third - the shape of a
        // broker timing out or a primary stepping down mid-write.
        AtomicInteger saves = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
                    if (saves.incrementAndGet() == 3) {
                        throw new IllegalStateException("simulated write failure on the third beneficiary");
                    }
                    // Through MongoTemplate rather than callRealMethod(): the bean under the spy
                    // is a Spring Data interface proxy with no method body to call. The template
                    // joins the projector's transaction, so the write is genuinely part of it and
                    // genuinely rolls back.
                    return mongo.save(invocation.getArgument(0));
                })
                .when(dashboards)
                .save(any(AgentDashboardDocument.class));

        assertThatThrownBy(() -> projector.apply(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated write failure");

        // The two dashboards that were written before the failure are gone. Without a
        // transaction they would still be here, and the redelivery that follows would add
        // their awards a second time.
        assertThat(mongo.findAll(AgentDashboardDocument.class)).isEmpty();

        // And the marker is gone too, so the event is still eligible for redelivery. Had it
        // survived, the retry would be skipped as a duplicate and the distribution would be
        // lost for good - a dashboard permanently short with nothing to indicate it.
        assertThat(mongo.findAll(ProcessedEventDocument.class)).isEmpty();
    }

    @Test
    void theRetryAfterARollbackProjectsTheWholeDistribution() {
        List<AgentId> upline = List.of(TestEvents.agent(), TestEvents.agent(), TestEvents.agent());
        RevenueShareDistributed event =
                TestEvents.upline(upline, TestEvents.agent(), TestEvents.transaction(), "80000.00");

        AtomicInteger saves = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
                    if (saves.incrementAndGet() == 3) {
                        throw new IllegalStateException("simulated write failure on the third beneficiary");
                    }
                    // Through MongoTemplate rather than callRealMethod(): the bean under the spy
                    // is a Spring Data interface proxy with no method body to call. The template
                    // joins the projector's transaction, so the write is genuinely part of it and
                    // genuinely rolls back.
                    return mongo.save(invocation.getArgument(0));
                })
                .when(dashboards)
                .save(any(AgentDashboardDocument.class));

        assertThatThrownBy(() -> projector.apply(event)).isInstanceOf(IllegalStateException.class);

        // Second delivery, with the fault cleared. This is what the consumer's retry does.
        Mockito.doAnswer(invocation -> mongo.save(invocation.getArgument(0)))
                .when(dashboards)
                .save(any(AgentDashboardDocument.class));

        assertThat(projector.apply(event)).isTrue();

        // Each beneficiary paid exactly once: the rolled-back attempt contributed nothing, and
        // the retry was not mistaken for a duplicate.
        assertThat(mongo.findAll(AgentDashboardDocument.class)).hasSize(3);
        assertThat(dashboards
                        .findById(upline.getFirst().toString())
                        .orElseThrow()
                        .getRevenueShare()
                        .getTotalAwarded())
                .isEqualByComparingTo("4000.00");
    }
}
