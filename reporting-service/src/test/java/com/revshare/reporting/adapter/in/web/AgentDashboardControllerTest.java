package com.revshare.reporting.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revshare.reporting.service.AgentDashboardQuery;
import com.revshare.reporting.service.AgentDashboardView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract, with the query stubbed.
 *
 * <p>A slice test and a mock, which is a deliberate departure from how the rest of this repository tests things. The
 * justification is that what is under test here is genuinely only the web layer: routing, status codes, id parsing and
 * the JSON shape. Feeding it through a real Mongo would add a container to prove nothing extra — the projection is
 * already covered against a real database in {@code DashboardProjectionIT}, and {@code AgentDashboardApiIT} joins the
 * two ends once.
 *
 * <p>Stubbing {@link AgentDashboardQuery} is also what makes the not-found and malformed-id cases expressible at all.
 * Both are states of the query result rather than states of the database, and arranging them through a real read model
 * would mean setting up the absence of data to test the handling of absent data.
 */
@WebMvcTest(AgentDashboardController.class)
@DisplayName("the dashboard endpoint")
class AgentDashboardControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AgentDashboardQuery dashboards;

    private static final String AGENT = "6f1a5b2c-0000-4000-8000-000000000003";

    private static AgentDashboardView view() {
        return new AgentDashboardView(
                AGENT,
                new AgentDashboardView.CapProgress(
                        new AgentDashboardView.CapYear(LocalDate.of(2025, 3, 14), LocalDate.of(2026, 3, 14), 0),
                        new BigDecimal("1500.00"),
                        new BigDecimal("12000.00"),
                        new BigDecimal("10500.00"),
                        false,
                        null),
                new AgentDashboardView.Production(
                        2,
                        new BigDecimal("30000.00"),
                        new BigDecimal("25500.00"),
                        new BigDecimal("4500.00"),
                        BigDecimal.ZERO),
                new AgentDashboardView.RevenueShare(
                        new BigDecimal("900.00"),
                        BigDecimal.ZERO,
                        List.of(new AgentDashboardView.Tier(
                                "TIER_1",
                                1,
                                "5%",
                                new BigDecimal("900.00"),
                                BigDecimal.ZERO,
                                1,
                                List.of("a-contributor")))),
                Instant.parse("2025-04-01T17:30:00Z"));
    }

    @Test
    void servesTheDashboardAsJson() throws Exception {
        when(dashboards.findByAgentId(any())).thenReturn(Optional.of(view()));

        mvc.perform(get("/agents/{id}/dashboard", AGENT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.agentId").value(AGENT))
                .andExpect(jsonPath("$.capProgress.remaining").value(10500.00))
                .andExpect(jsonPath("$.production.closings").value(2))
                .andExpect(jsonPath("$.revenueShare.tiers[0].tier").value("TIER_1"));
    }

    @Test
    void moneyIsSerialisedAsANumberAtItsNaturalScale() throws Exception {
        when(dashboards.findByAgentId(any())).thenReturn(Optional.of(view()));

        // Not a string, and not rounded to 10500.0 - a client summing these must not have to
        // parse, and a trailing cent must not disappear on the way out.
        mvc.perform(get("/agents/{id}/dashboard", AGENT))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"remaining\":10500.00")));
    }

    @Test
    void datesAreIso8601NotEpochNumbers() throws Exception {
        when(dashboards.findByAgentId(any())).thenReturn(Optional.of(view()));

        mvc.perform(get("/agents/{id}/dashboard", AGENT))
                .andExpect(jsonPath("$.capProgress.capYear.start").value("2025-03-14"))
                .andExpect(jsonPath("$.lastProjectedAt").value("2025-04-01T17:30:00Z"));
    }

    @Test
    void anAgentWithNoProjectedDashboardIs404() throws Exception {
        when(dashboards.findByAgentId(any())).thenReturn(Optional.empty());

        mvc.perform(get("/agents/{id}/dashboard", AGENT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Dashboard not found"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(AGENT)));
    }

    @Test
    void aMalformedIdIs400NotA404() throws Exception {
        // The distinction matters to a caller: "you asked wrongly" and "there is nothing here"
        // send them looking in different places.
        mvc.perform(get("/agents/{id}/dashboard", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed agent id"));

        org.mockito.Mockito.verifyNoInteractions(dashboards);
    }

    @Test
    void theReadSideRefusesWrites() throws Exception {
        // Structural, not a gap. State is changed by publishing a closing to commission-service;
        // a second way in with no cap arithmetic behind it would let the two models disagree.
        mvc.perform(post("/agents/{id}/dashboard", AGENT)).andExpect(status().isMethodNotAllowed());
    }
}
