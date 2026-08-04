package com.revshare.commission.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.revshare.commission.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The agent lifecycle API over a real write path.
 *
 * <p>Until this endpoint existed the sponsorship tree could only be built by calling {@code AgentRepository.save} from
 * inside the JVM, which announced nothing — so the read side never learned an agent existed until money moved on their
 * behalf. These tests drive the surface that closes that.
 */
@AutoConfigureMockMvc
@DisplayName("the agent API over a real write path")
class AgentApiIT extends AbstractPostgresIT {

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mvc;

    private static int sequence;

    private static String enrolment(UUID agentId, UUID sponsorId) {
        int n = ++sequence;
        String sponsor = sponsorId == null ? "null" : "\"" + sponsorId + "\"";
        return """
                {
                  "agentId": "%s",
                  "firstName": "Test",
                  "lastName": "Agent%d",
                  "email": "api%d@example.test",
                  "joinedOn": "2024-01-15",
                  "sponsorId": %s
                }
                """.formatted(agentId, n, n, sponsor);
    }

    private ResultActions enroll(UUID agentId, UUID sponsorId) throws Exception {
        return mvc.perform(
                post("/agents").contentType(MediaType.APPLICATION_JSON).content(enrolment(agentId, sponsorId)));
    }

    private ResultActions terminate(UUID agentId, String on) throws Exception {
        return mvc.perform(post("/agents/{id}/termination", agentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminatedOn\": \"" + on + "\"}"));
    }

    @Nested
    @DisplayName("enrolment")
    class Enrolment {

        @Test
        @DisplayName("returns the agent with the path the brokerage derived")
        void enrolsAndReturnsTheDerivedPath() throws Exception {
            UUID top = UUID.randomUUID();
            UUID middle = UUID.randomUUID();
            UUID bottom = UUID.randomUUID();

            enroll(top, null).andExpect(status().isCreated());
            enroll(middle, top).andExpect(status().isCreated());

            // The request never carries a path. A caller has no other way to see where the
            // brokerage placed them, which is why the response returns it.
            enroll(bottom, middle)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.agentId").value(bottom.toString()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.sponsorId").value(middle.toString()))
                    .andExpect(jsonPath("$.sponsorshipPath[0]").value(middle.toString()))
                    .andExpect(jsonPath("$.sponsorshipPath[1]").value(top.toString()));
        }

        @Test
        @DisplayName("serves an agent at the top of a tree with an empty path")
        void anUnsponsoredAgentHasNoPath() throws Exception {
            enroll(UUID.randomUUID(), null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sponsorId").doesNotExist())
                    .andExpect(jsonPath("$.sponsorshipPath").isEmpty());
        }

        @Test
        @DisplayName("answers 409 to a repeat enrolment rather than treating it as a replay")
        void duplicateEnrolmentIs409() throws Exception {
            UUID agentId = UUID.randomUUID();
            enroll(agentId, null).andExpect(status().isCreated());

            enroll(agentId, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Already enrolled"));
        }

        @Test
        @DisplayName("answers 404 for a sponsor that does not exist")
        void unknownSponsorIs404() throws Exception {
            // The request is well-formed and the id is a valid UUID; what is missing is an
            // agent, which is a fact about this service rather than about the payload.
            enroll(UUID.randomUUID(), UUID.randomUUID())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Unknown sponsor"));
        }

        @Test
        @DisplayName("names the fields that failed validation")
        void missingFieldsAre400() throws Exception {
            mvc.perform(post("/agents").contentType(MediaType.APPLICATION_JSON).content("{\"firstName\": \"Only\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid request"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("agentId")));
        }

        @Test
        @DisplayName("rejects an agent sponsoring themselves")
        void selfSponsorshipIs400() throws Exception {
            UUID agentId = UUID.randomUUID();

            enroll(agentId, agentId)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid enrolment"));
        }
    }

    @Nested
    @DisplayName("termination")
    class Termination {

        @Test
        @DisplayName("ends the affiliation and answers 200, not 201")
        void terminatesAnAgent() throws Exception {
            UUID agentId = UUID.randomUUID();
            enroll(agentId, null).andExpect(status().isCreated());

            // A state change to something that already exists, with no new resource to point at.
            terminate(agentId, "2024-07-15")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("TERMINATED"))
                    .andExpect(jsonPath("$.terminatedOn").value("2024-07-15"));
        }

        @Test
        @DisplayName("answers 409 to terminating an agent who has already left")
        void terminatingTwiceIs409() throws Exception {
            UUID agentId = UUID.randomUUID();
            enroll(agentId, null).andExpect(status().isCreated());
            terminate(agentId, "2024-07-15").andExpect(status().isOk());

            terminate(agentId, "2024-09-15")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Invalid state transition"));
        }

        @Test
        @DisplayName("answers 404 for an agent that was never enrolled")
        void unknownAgentIs404() throws Exception {
            terminate(UUID.randomUUID(), "2024-07-15")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Unknown agent"));
        }

        @Test
        @DisplayName("answers 400 to a malformed id rather than 404")
        void aMalformedIdIs400() throws Exception {
            mvc.perform(post("/agents/{id}/termination", "not-a-uuid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"terminatedOn\": \"2024-07-15\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Malformed agent id"));
        }
    }

    @Test
    @DisplayName("an enrolled agent can immediately close a transaction")
    void theTwoWritePathsMeet() throws Exception {
        UUID agentId = UUID.randomUUID();
        enroll(agentId, null).andExpect(status().isCreated());

        // The join that makes the endpoint worth having: an agent enrolled over HTTP is a real
        // agent to the closing path, with a cap year anchored on the join date it was given.
        String closing = """
                {
                  "transactionId": "%s",
                  "agentId": "%s",
                  "closedOn": "2024-04-15",
                  "salePrice": 1000000.00,
                  "grossCommissionIncome": 10000.00,
                  "side": "LISTING",
                  "propertyReference": "PROP-JOIN"
                }
                """.formatted(UUID.randomUUID(), agentId);

        mvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(closing))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.split.agentEarnings").value(8500.00))
                .andExpect(jsonPath("$.capProgress.capYear.start").value("2024-01-15"));
    }
}
