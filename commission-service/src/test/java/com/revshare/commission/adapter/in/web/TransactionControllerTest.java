package com.revshare.commission.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.port.in.RecordClosedTransaction;
import com.revshare.domain.port.out.CapProgressRepository;
import com.revshare.domain.revshare.RevenueShareDistribution;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The retry loop and the status codes, with the port stubbed by hand.
 *
 * <p><strong>Not a third mock.</strong> The repository has exactly two, and this is not one of them: the controller
 * takes {@link RecordClosedTransaction} as a constructor argument, so expressing "fail twice, then succeed" is a
 * fifteen-line class rather than a mocking framework. That is the check {@code CLAUDE.md} asks for before reaching for
 * Mockito — the dependency belonged in an argument, and it already was one.
 *
 * <p>It is also the only way to test what is here. Retry exhaustion is a request that loses the optimistic lock five
 * times in a row, and a real Postgres cannot be asked to lose a race on cue; {@code CapProgressConcurrencyIT} proves
 * the conflict is real with actual threads, and this proves what the web layer does about it. Everything reachable
 * without staging a failure — pricing, idempotency, unknown agents, the JSON shape — is tested against a real database
 * in {@code TransactionApiIT} instead.
 *
 * <p>{@code standaloneSetup} rather than {@code @WebMvcTest}: with the collaborator constructed by hand there is no
 * context to slice, and this way the test starts in milliseconds without one.
 */
@DisplayName("the closing endpoint")
class TransactionControllerTest {

    private static final AgentId AGENT = AgentId.newId();
    private static final LocalDate JOINED = LocalDate.of(2024, 1, 15);
    private static final LocalDate CLOSED_ON = LocalDate.of(2024, 4, 15);

    private static MockMvc mvcFor(RecordClosedTransaction port) {
        return MockMvcBuilders.standaloneSetup(new TransactionController(port))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static String body(String transactionId) {
        return """
                {
                  "transactionId": "%s",
                  "agentId": "%s",
                  "closedOn": "2024-04-15",
                  "salePrice": 1000000.00,
                  "grossCommissionIncome": 10000.00,
                  "side": "LISTING",
                  "propertyReference": "PROP-1"
                }
                """.formatted(transactionId, AGENT);
    }

    @Test
    @DisplayName("re-prices against the winner and still succeeds when the cap row is contended")
    void retriesAConcurrentCapUpdate() throws Exception {
        // Two losses then a win. The recovery is invisible to the caller by design: re-pricing
        // against the winner's balance is the only correct response to an optimistic-lock
        // failure, so there is nothing for a client to decide.
        StubPort port = StubPort.failing(2);

        mvcFor(port)
                .perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(java.util.UUID.randomUUID().toString())))
                .andExpect(status().isCreated());

        assertThat(port.attempts)
                .as("the loop must have made a fresh attempt per conflict, not retried inside one")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("gives up with a retryable 409 rather than looping forever")
    void exhaustsTheRetryBudget() throws Exception {
        StubPort port = StubPort.failing(Integer.MAX_VALUE);

        mvcFor(port)
                .perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(java.util.UUID.randomUUID().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cap update conflict"))
                // A 409 can also mean "and it never will". Saying which one this is saves a
                // client from guessing whether retrying is pointless.
                .andExpect(jsonPath("$.retryable").value(true));

        assertThat(port.attempts).isEqualTo(5);
    }

    @Test
    @DisplayName("answers 200 rather than 201 for a closing already recorded")
    void aReplayIsNotACreation() throws Exception {
        StubPort port = StubPort.replaying();

        mvcFor(port)
                .perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(java.util.UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyRecorded").value(true))
                // The status line is what a client retrying after a timeout reads to learn
                // whether its first attempt landed, without having to parse the body.
                .andExpect(jsonPath("$.split.agentEarnings").value(8500.00));
    }

    @Test
    @DisplayName("omits the revenue share block on a replay instead of reporting zeros")
    void aReplayDoesNotClaimNobodyWasPaid() throws Exception {
        // The service's replay path reconstructs the split and the cap from storage but returns
        // an empty distribution - it deliberately does not re-read the ledger. Serialising that
        // as "totalAwarded: 0" would state as fact that this closing paid nobody, when the truth
        // is that the original recording paid an upline this response cannot see.
        mvcFor(StubPort.replaying())
                .perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(java.util.UUID.randomUUID().toString())))
                .andExpect(jsonPath("$.revenueShare").doesNotExist());
    }

    @Test
    @DisplayName("reports every invalid field at once rather than the first")
    void namesTheFieldsThatFailedValidation() throws Exception {
        String missingFields = """
                { "agentId": "%s", "salePrice": 1000000.00, "side": "LISTING" }
                """.formatted(AGENT);

        mvcFor(StubPort.failing(0))
                .perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingFields))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.length()").value(4))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("transactionId")));
    }

    @Test
    @DisplayName("rejects a side outside the enum without echoing Jackson's internals")
    void anUnknownSideIs400() throws Exception {
        String badSide = body(java.util.UUID.randomUUID().toString()).replace("LISTING", "REFERRAL");

        mvcFor(StubPort.failing(0))
                .perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badSide))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unreadable request body"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("LISTING, BUYING or DUAL")));
    }

    /**
     * A {@link RecordClosedTransaction} that fails a fixed number of times, then answers.
     *
     * <p>Counts its calls, because the count is half of what the retry tests assert: a loop that recovered but only
     * ever called the port once would mean the retry happened inside a transaction Postgres had already aborted.
     */
    private static final class StubPort implements RecordClosedTransaction {

        private final int failuresBeforeSuccess;
        private final Supplier<Receipt> answer;
        private int attempts;

        private StubPort(int failuresBeforeSuccess, Supplier<Receipt> answer) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.answer = answer;
        }

        static StubPort failing(int times) {
            return new StubPort(times, () -> receipt(false));
        }

        static StubPort replaying() {
            return new StubPort(0, () -> receipt(true));
        }

        @Override
        public Receipt record(ClosedTransaction transaction) {
            if (++attempts <= failuresBeforeSuccess) {
                throw new CapProgressRepository.ConcurrentCapUpdateException("another writer advanced the cap first");
            }
            return answer.get();
        }

        private static Receipt receipt(boolean alreadyRecorded) {
            CommissionSplit split = new CommissionSplit(
                    com.revshare.domain.transaction.TransactionId.newId(),
                    AGENT,
                    CLOSED_ON,
                    Money.of("10000.00"),
                    Money.of("8500.00"),
                    Money.of("1500.00"),
                    Money.of("1500.00"),
                    Money.ZERO,
                    Money.of("10000.00"),
                    false,
                    false);

            CapProgress progress = new CapProgress(
                    AGENT, CapYear.containing(JOINED, CLOSED_ON), Money.of("1500.00"), Money.of("12000.00"));

            return new Receipt(
                    split,
                    progress,
                    RevenueShareDistribution.none(split.transactionId(), AGENT, CLOSED_ON, Money.of("10000.00")),
                    alreadyRecorded);
        }
    }
}
