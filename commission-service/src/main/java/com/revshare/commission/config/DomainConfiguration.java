package com.revshare.commission.config;

import com.revshare.domain.commission.CommissionCalculator;
import com.revshare.domain.commission.CommissionPlan;
import com.revshare.domain.revshare.ProducingAgentPolicy;
import com.revshare.domain.revshare.RevenueShareCalculator;
import com.revshare.domain.revshare.RevenueSharePlan;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the domain's stateless services and schedules as beans.
 *
 * <p>The core has no Spring annotations, so its wiring is declared here instead. That is the arrangement working as
 * intended rather than a workaround: the calculators are plain objects that can be constructed in a unit test with
 * {@code new}, and this class is the only place that knows they participate in a container.
 */
@Configuration
public class DomainConfiguration {

    /**
     * The schedule in force for newly priced closings.
     *
     * <p>A single bean today. When the schedule changes, this becomes a lookup by effective date, because recomputing a
     * two-year-old statement has to use the plan that was in force when the deal closed rather than today's — which is
     * why every calculator takes the plan as an argument instead of reaching for a constant.
     */
    @Bean
    public CommissionPlan commissionPlan() {
        return CommissionPlan.standard();
    }

    /**
     * The revenue share schedule, bound to the commission plan that funds it.
     *
     * <p>Constructed from the same bean rather than independently, so the five published per-tier annual maxima stay
     * derived from the cap and the split. Constructing it with a different plan would be caught immediately:
     * {@code RevenueSharePlan} asserts that the tier rates sum to no more than the company's share.
     */
    @Bean
    public RevenueSharePlan revenueSharePlan(CommissionPlan commissionPlan) {
        return new RevenueSharePlan(commissionPlan);
    }

    @Bean
    public ProducingAgentPolicy producingAgentPolicy() {
        return ProducingAgentPolicy.standard();
    }

    @Bean
    public CommissionCalculator commissionCalculator() {
        return new CommissionCalculator();
    }

    @Bean
    public RevenueShareCalculator revenueShareCalculator() {
        return new RevenueShareCalculator();
    }

    /**
     * Injected rather than read via {@code Instant.now()} at the call site.
     *
     * <p>Event timestamps are the one place this service legitimately needs the current time, and having it arrive as a
     * dependency means a test can pin it and assert on the emitted events instead of tolerating whatever the wall clock
     * said.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
