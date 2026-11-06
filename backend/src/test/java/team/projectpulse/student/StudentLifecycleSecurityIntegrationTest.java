package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.section.Section;
import team.projectpulse.user.UserRepository;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Oracles: UC-STU-deactivate-student 2a, FR-SEC-authorization, and BR-section-scoped-access. */
class StudentLifecycleSecurityIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired JwtEncoder encoder;
    @Value("${api.endpoint.base-url}") String base;

    @Test
    void owningAdminWithoutSectionAssignmentOrLiteralInstructorRoleCanReadStudent() throws Exception {
        this.em.find(Section.class, 2).getInstructors().removeIf(i -> i.getId().equals(1));
        this.em.flush();
        Instant now = Instant.now();
        String token = this.encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .subject("b.wei@abc.edu").claim("userId", 1).claim("authorities", "ROLE_admin")
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build())).getTokenValue();
        this.mvc.perform(get(this.base + "/students/4").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(4));
    }

    @Test
    void lifecycleRouteDeniesStudentUnassignedInstructorAndUnrelatedAdmin() throws Exception {
        for (String email : new String[]{"j.smith@abc.edu", "t.cook@abc.edu"}) {
            denied(login(email), "4");
        }
        this.em.find(Section.class, 2).getInstructors().removeIf(i -> i.getId().equals(2));
        this.em.flush();
        denied(login("b.gates@abc.edu"), "4");
    }

    @Test
    void unauthorizedInactiveTargetDoesNotDiscloseAlreadyInactiveOutcome() throws Exception {
        this.em.find(Student.class, 4).setEnabled(false);
        this.em.flush();
        denied(login("t.cook@abc.edu"), "4");
    }

    @Test
    void invalidTargetFailsClosedAndAnonymousCallerIsUnauthenticated() throws Exception {
        String token = login("b.wei@abc.edu");
        for (String id : new String[]{"999999", "not-an-id", "-1", "2147483648"}) denied(token, id);
        this.mvc.perform(post(this.base + "/students/4/deactivate"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false));
    }

    private void denied(String token, String id) throws Exception {
        this.mvc.perform(post(this.base + "/students/" + id + "/deactivate")
                        .header("Authorization", token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("No permission."));
    }

    private String login(String email) throws Exception {
        var r = this.mvc.perform(post(this.base + "/users/login").with(httpBasic(email, "123456")))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + new JSONObject(r.getResponse().getContentAsString()).getJSONObject("data").getString("token");
    }
}
