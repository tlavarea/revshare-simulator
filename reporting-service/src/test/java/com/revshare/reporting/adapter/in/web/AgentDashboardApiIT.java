package com.revshare.reporting.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.revshare.RevenueShareTier;
import com.revshare.reporting.AbstractMongoIT;
import com.revshare.reporting.TestEvents;
import com.revshare.reporting.service.DashboardProjector;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Events in, JSON out, through the real stack.
 *
 * <p>The join that neither of the two tests either side of it can make. {@code AgentDashboardControllerTest} stubs the
 * query and so cannot tell whether the projection really produces the shape it claims; {@code DashboardProjectionIT}
 * reads the document back through a repository and so never exercises the rendering. This projects real events into a
 * real Mongo and then asks the endpoint what it sees.
 *
 * <p>The projector is driven directly rather than through Kafka. Delivery is already covered end to end by
 * {@code EventStreamIT}, and a broker here would add ten seconds to test a hop that has nothing to do with the API.
 */
@AutoConfigureMockMvc
@DisplayName("the dashboard API over a projected read model")
class AgentDashboardApiIT extends AbstractMongoIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DashboardProjector projector;

    @Test
    void aProjectedAgentIsServedWithTheFiguresTheEventsCarried() throws Exception {
        AgentId agent = TestEvents.agent();

        projector.apply(
                TestEvents.closing(agent, TestEvents.transaction(), LocalDate.of(2025, 4, 1), "10000.00", "1500.00"));
        projector.apply(
                TestEvents.closing(agent, TestEvents.transaction(), LocalDate.of(2025, 5, 1), "20000.00", "4500.00"));

        mvc.perform(get("/agents/{id}/dashboard", agent.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agent.toString()))
                .andExpect(jsonPath("$.production.closings").value(2))
                .andExpect(jsonPath("$.production.grossCommissionIncome").value(30000.00))
                .andExpect(jsonPath("$.capProgress.contributed").value(4500.00))
                .andExpect(jsonPath("$.capProgress.remaining").value(7500.00))
                .andExpect(jsonPath("$.capProgress.capped").value(false))
                .andExpect(jsonPath("$.capProgress.capYear.start").value("2025-03-14"));
    }

    @Test
    void allFiveTiersAreServedEvenWhenOnlyOneHasEarned() throws Exception {
        AgentId sponsor = TestEvents.agent();
        AgentId seller = TestEvents.agent();

        projector.apply(TestEvents.award(
                sponsor, seller, TestEvents.transaction(), RevenueShareTier.TIER_3, "10000.00", "300.00"));

        // A client rendering a five-tier programme should not have to know that five is the
        // number, nor treat an absent key differently from a zero.
        mvc.perform(get("/agents/{id}/dashboard", sponsor.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenueShare.tiers.length()").value(5))
                .andExpect(jsonPath("$.revenueShare.tiers[0].tier").value("TIER_1"))
                .andExpect(jsonPath("$.revenueShare.tiers[0].awarded").value(0))
                .andExpect(jsonPath("$.revenueShare.tiers[0].contributorCount").value(0))
                .andExpect(jsonPath("$.revenueShare.tiers[2].tier").value("TIER_3"))
                .andExpect(jsonPath("$.revenueShare.tiers[2].depth").value(3))
                .andExpect(jsonPath("$.revenueShare.tiers[2].awarded").value(300.00))
                .andExpect(jsonPath("$.revenueShare.tiers[2].contributors[0]").value(seller.toString()));
    }

    @Test
    void aCappedAgentReportsTheDateAndZeroRemaining() throws Exception {
        AgentId agent = TestEvents.agent();
        LocalDate reachedOn = LocalDate.of(2025, 9, 22);

        projector.apply(TestEvents.closing(agent, TestEvents.transaction(), reachedOn, "80000.00", "12000.00"));
        projector.apply(TestEvents.capped(agent, TestEvents.transaction(), reachedOn));

        mvc.perform(get("/agents/{id}/dashboard", agent.toString()))
                .andExpect(jsonPath("$.capProgress.capped").value(true))
                .andExpect(jsonPath("$.capProgress.cappedOn").value("2025-09-22"))
                .andExpect(jsonPath("$.capProgress.remaining").value(0));
    }

    @Test
    void anAgentKnownOnlyFromRevenueShareHasNoCapYearYet() throws Exception {
        AgentId sponsor = TestEvents.agent();

        projector.apply(TestEvents.award(
                sponsor, TestEvents.agent(), TestEvents.transaction(), RevenueShareTier.TIER_1, "10000.00", "500.00"));

        // A real state, not an edge case invented for the test: an agent can earn from their
        // downline before closing anything themselves, and the dashboard has to render with no
        // commission event ever having named them.
        mvc.perform(get("/agents/{id}/dashboard", sponsor.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capProgress.capYear").doesNotExist())
                .andExpect(jsonPath("$.production.closings").value(0))
                .andExpect(jsonPath("$.revenueShare.totalAwarded").value(500.00));
    }

    @Test
    void anAgentNoEventHasEverNamedIs404() throws Exception {
        mvc.perform(get("/agents/{id}/dashboard", AgentId.newId().toString())).andExpect(status().isNotFound());
    }

    @Test
    void theWholeUplineIsQueryableAfterOneDistribution() throws Exception {
        List<AgentId> upline = List.of(TestEvents.agent(), TestEvents.agent(), TestEvents.agent());
        AgentId seller = TestEvents.agent();

        projector.apply(TestEvents.upline(upline, seller, TestEvents.transaction(), "80000.00"));

        mvc.perform(get("/agents/{id}/dashboard", upline.getFirst().toString()))
                .andExpect(jsonPath("$.revenueShare.totalAwarded").value(4000.00));
        mvc.perform(get("/agents/{id}/dashboard", upline.get(2).toString()))
                .andExpect(jsonPath("$.revenueShare.totalAwarded").value(2400.00));
    }
}
