package com.revshare.commission.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.revshare.commission.AbstractPostgresIT;
import com.revshare.commission.TestBrokerage;
import com.revshare.domain.agent.Agent;
import com.revshare.domain.port.in.RecordClosedTransaction;
import com.revshare.domain.port.out.AgentRepository;
import com.revshare.domain.port.out.CapProgressRepository;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the concurrency guarantee {@code CapProgressRepository} promises.
 *
 * <p>This is the test the whole {@code @Version} column exists for. Cap progress is read-modify-write, and without
 * optimistic locking two closings priced in parallel for one agent would both read the same balance, both compute a
 * full 15% share, and both write — the second silently overwriting the first. The agent would end the year
 * under-contributed and free to earn past their cap, and nothing in the resulting data would show it: both rows look
 * individually valid, and the totals only disagree if someone re-adds them.
 *
 * <p>A unit test cannot catch this. It needs real transactions against a real database, with threads actually racing.
 */
class CapProgressConcurrencyIT extends AbstractPostgresIT {

    private static final int CONCURRENT_CLOSINGS = 8;

    /** 15% of $10,000 is $1,500; eight of those is $12,000, exactly the cap. */
    private static final String GROSS_PER_CLOSING = "10000.00";

    @Autowired
    private RecordClosedTransaction recordClosedTransaction;

    @Autowired
    private AgentRepository agents;

    @Autowired
    private CapProgressRepository capProgress;

    private TestBrokerage brokerage;

    @BeforeEach
    void setUp() {
        brokerage = new TestBrokerage(agents);
    }

    @Test
    @DisplayName("concurrent closings for one agent cannot lose a cap contribution")
    void concurrentClosingsDoNotLoseContributions() throws Exception {
        Agent agent = brokerage.founder();

        List<ClosedTransaction> closings = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CLOSINGS; i++) {
            closings.add(TestBrokerage.closing(agent, GROSS_PER_CLOSING));
        }

        AtomicInteger retries = new AtomicInteger();
        runConcurrently(closings, retries);

        var progress = capProgress
                .find(agent.id(), agent.capYearOn(closings.get(0).closedOn()))
                .orElseThrow();

        // Every one of the eight contributions landed. Under a lost update this comes out
        // short — and short by a clean multiple of $1,500, which is exactly what makes the
        // bug so easy to mistake for correct data.
        assertThat(progress.contributed())
                .as("all %d contributions must be accounted for", CONCURRENT_CLOSINGS)
                .isEqualTo(Money.of("12000.00"));
        assertThat(progress.isCapped()).isTrue();

        // The retries prove the race actually happened rather than the threads having
        // politely queued up. If this is ever zero the test has stopped testing anything.
        assertThat(retries.get())
                .as("expected at least one optimistic-lock conflict; otherwise nothing raced")
                .isPositive();
    }

    @Test
    @DisplayName("concurrent closings can never push an agent past the cap")
    void concurrentClosingsCannotExceedTheCap() throws Exception {
        Agent agent = brokerage.founder();

        // Twice as much production as it takes to cap, all arriving at once.
        List<ClosedTransaction> closings = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CLOSINGS * 2; i++) {
            closings.add(TestBrokerage.closing(agent, GROSS_PER_CLOSING));
        }

        runConcurrently(closings, new AtomicInteger());

        var progress = capProgress
                .find(agent.id(), agent.capYearOn(closings.get(0).closedOn()))
                .orElseThrow();

        // The cap holds exactly. ck_cap_progress_within_cap would have rejected anything
        // above it at the database level, so reaching this assertion at all means no writer
        // ever tried.
        assertThat(progress.contributed()).isEqualTo(Money.of("12000.00"));
    }

    /**
     * Fires every closing at once and retries the losers.
     *
     * <p>The retry loop is the point, not a workaround. Optimistic locking converts a silent lost update into a loud,
     * retryable failure; the caller's job is to re-read and re-price against the winner's state, which is exactly what
     * calling {@code record} again does.
     */
    private void runConcurrently(List<ClosedTransaction> closings, AtomicInteger retries) throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(closings.size())) {
            List<Callable<Void>> tasks = closings.stream()
                    .map(closing -> (Callable<Void>) () -> {
                        startGun.await();
                        for (int attempt = 0; attempt < 50; attempt++) {
                            try {
                                recordClosedTransaction.record(closing);
                                return null;
                            } catch (CapProgressRepository.ConcurrentCapUpdateException
                                    | org.springframework.dao.OptimisticLockingFailureException e) {
                                retries.incrementAndGet();
                                Thread.sleep(5);
                            }
                        }
                        throw new AssertionError("gave up retrying " + closing.id());
                    })
                    .toList();

            List<Future<Void>> futures = new ArrayList<>();
            tasks.forEach(task -> futures.add(pool.submit(task)));
            startGun.countDown();

            for (Future<Void> future : futures) {
                future.get();
            }
        }
    }
}
