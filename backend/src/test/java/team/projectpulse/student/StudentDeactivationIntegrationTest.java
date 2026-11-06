package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.section.Section;
import team.projectpulse.user.UserRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Oracles: deactivation main flow, 3a, membership/preservation rules, and dedicated-action contract. */
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired StudentService service;
    @Autowired JwtEncoder encoder;
    @Value("${api.endpoint.base-url}") String base;

    @Test
    void deactivationPreservesMembershipProfileAndEveryStoredWorkRow() throws Exception {
        var activities = snapshot("activity", "activity_id");
        var evaluations = snapshot("peer_evaluation", "peer_evaluation_id");
        var ratings = snapshot("rating", "rating_id");
        var student = this.em.find(Student.class, 4);
        Integer team = student.getTeam().getTeamId();
        String username = student.getUsername();
        this.mvc.perform(post(this.base + "/students/4/deactivate").header("Authorization", login("b.gates@abc.edu")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data.outcome").value("deactivated"))
                .andExpect(jsonPath("$.data.student.enabled").value(false))
                .andExpect(jsonPath("$.data.student.teamId").value(team));
        this.em.flush(); this.em.clear();
        assertThat(this.em.find(Student.class, 4).getUsername()).isEqualTo(username);
        assertThat(snapshot("activity", "activity_id")).usingRecursiveComparison().isEqualTo(activities);
        assertThat(snapshot("peer_evaluation", "peer_evaluation_id")).usingRecursiveComparison().isEqualTo(evaluations);
        assertThat(snapshot("rating", "rating_id")).usingRecursiveComparison().isEqualTo(ratings);
    }

    @Test
    void ownerWithoutSectionAssignmentOrInstructorRoleCanDeactivate() throws Exception {
        this.em.find(Section.class, 2).getInstructors().removeIf(i -> i.getId().equals(1));
        this.em.flush();
        Instant now = Instant.now();
        String token = this.encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder().subject("b.wei@abc.edu")
                .claim("userId", 1).claim("authorities", "ROLE_admin")
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build())).getTokenValue();
        this.mvc.perform(post(this.base + "/students/4/deactivate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.student.enabled").value(false));
    }

    @Test
    void unassignedStudentAndRepeatWorkWithoutAnyActiveStudentRemaining() throws Exception {
        String token = login("b.wei@abc.edu");
        int id = this.users.findByUsername("t.nicholson@abc.edu").orElseThrow().getId();
        this.mvc.perform(post(this.base + "/students/" + id + "/deactivate").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("deactivated"))
                .andExpect(jsonPath("$.data.student.teamId").isEmpty());
        this.em.createQuery("update Student s set s.enabled = false").executeUpdate();
        this.mvc.perform(post(this.base + "/students/" + id + "/deactivate").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("already-inactive"));
    }

    @Test
    void serviceAlsoRejectsCallerOutsideScopeWithoutRelyingOnRouteRule() {
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("t.cook@abc.edu").claim("userId", 3).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_admin"), new SimpleGrantedAuthority("ROLE_instructor"))));
        try {
            assertThatThrownBy(() -> this.service.deactivateStudent(4)).isInstanceOf(AccessDeniedException.class);
            assertThat(this.em.find(Student.class, 4).isEnabled()).isTrue();
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void profileCannotBypassActionButOrdinaryEditStillWorks() throws Exception {
        String token = login("b.wei@abc.edu");
        String body = """
                {"username":"j.smith@abc.edu","firstName":"Changed","lastName":"Smith",
                 "email":"j.smith@abc.edu","enabled":false}
                """;
        this.mvc.perform(put(this.base + "/students/4").header("Authorization", token)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Deactivate student")));
        assertThat(this.em.find(Student.class, 4).getFirstName()).isEqualTo("John");
        this.mvc.perform(put(this.base + "/students/4").header("Authorization", token)
                        .contentType("application/json").content(body.replace("false", "true")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.firstName").value("Changed"))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    private List<?> snapshot(String table, String key) {
        return this.em.createNativeQuery("select * from " + table + " order by " + key).getResultList();
    }
    private String login(String email) throws Exception {
        var r = this.mvc.perform(post(this.base + "/users/login").with(httpBasic(email, "123456"))).andReturn();
        return "Bearer " + new JSONObject(r.getResponse().getContentAsString()).getJSONObject("data").getString("token");
    }
}
