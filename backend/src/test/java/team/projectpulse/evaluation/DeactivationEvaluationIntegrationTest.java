package team.projectpulse.evaluation;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.student.Student;
import team.projectpulse.student.StudentRepository;
import team.projectpulse.student.StudentService;
import team.projectpulse.system.exception.PeerEvaluationIllegalArgumentException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Oracles: active-evaluatee, inactive-history, submission-owed, and existing edit-window rules. */
class DeactivationEvaluationIntegrationTest extends AbstractIntegrationTest {
    @Autowired EntityManager em;
    @Autowired EvaluationService evaluations;
    @Autowired PeerEvaluationRepository repository;
    @Autowired StudentService students;
    @Autowired StudentRepository studentRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Value("${api.endpoint.base-url}") String base;

    @Test
    void newEvaluationOfInactiveTeammateIsRejectedWhileActiveTeammateStillWorks() throws Exception {
        String evaluator = login("w.allen@abc.edu");
        deactivate(8);
        long before = this.repository.count();
        this.mvc.perform(post(this.base + "/evaluations").header("Authorization", evaluator)
                        .contentType("application/json").content(payload(8, "2023-W32", 9)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));
        assertThat(this.repository.count()).isEqualTo(before);
        this.mvc.perform(post(this.base + "/evaluations").header("Authorization", evaluator)
                        .contentType("application/json").content(payload(9, "2023-W32", 9)))
                .andExpect(status().isOk());
    }

    @Test
    void historicalRowsDependOnReceivedWeekAndKeepInactiveEvaluatorsScoresAndComments() throws Exception {
        Student woody = this.em.find(Student.class, 7);
        this.repository.findByWeekAndEvaluateeId("2023-W31", 7).forEach(this.repository::delete);
        this.repository.findByWeekAndEvaluateeId("2023-W31", 9).forEach(this.repository::delete);
        this.repository.flush();
        this.repository.save(new PeerEvaluation("2023-W31", this.em.find(Student.class, 8), woody,
                ratings(10,10,10,10,9,9), "First public", "First private"));
        this.repository.save(new PeerEvaluation("2023-W31", this.em.find(Student.class, 9), woody,
                ratings(10,10,10,10,5,5), "Second public", "Second private"));
        assertThat(summary("2023-W31", 7).getAverageTotalScore()).isEqualTo(54);
        deactivate(7); deactivate(9);
        woody.setTeam(null); this.em.flush();
        PeerEvaluationAverage row = summary("2023-W31", 7);
        assertThat(row.isEnabled()).isFalse();
        assertThat(row.getTeamName()).isNull();
        assertThat(row.getAverageTotalScore()).isEqualTo(54);
        assertThat(row.getPublicComments()).containsExactlyInAnyOrder("First public", "Second public");
        assertThat(row.getPrivateComments()).containsExactlyInAnyOrder("First private", "Second private");
        assertThat(this.evaluations.generateWeeklyPeerEvaluationReportForSection(2, "2023-W32").getPeerEvaluationAverages())
                .extracting(PeerEvaluationAverage::getStudentId).doesNotContain(7, 9);
        assertThat(this.evaluations.generateWeeklyPeerEvaluationReportForSection(2, "2023-W31").getPeerEvaluationAverages())
                .extracting(PeerEvaluationAverage::getStudentId).doesNotContain(9); // Authored-only is insufficient.
    }

    @Test
    void existingEvaluationOfInactiveTeammateRemainsEditableOnlyInsideItsWindow() throws Exception {
        String evaluator = login("w.allen@abc.edu");
        PeerEvaluation open = this.repository.saveAndFlush(new PeerEvaluation("2023-W32", this.em.find(Student.class, 7),
                this.em.find(Student.class, 8), ratings(9,9,9,9,9,9), "Before", "Private"));
        PeerEvaluation closed = this.repository.saveAndFlush(new PeerEvaluation("2023-W31", this.em.find(Student.class, 7),
                this.em.find(Student.class, 8), ratings(9,9,9,9,9,9), "Before", "Private"));
        deactivate(8);
        this.mvc.perform(put(this.base + "/evaluations/" + open.getPeerEvaluationId()).header("Authorization", evaluator)
                        .contentType("application/json").content(payload(8, "2023-W32", 8)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalScore").value(48));
        this.mvc.perform(put(this.base + "/evaluations/" + closed.getPeerEvaluationId()).header("Authorization", evaluator)
                        .contentType("application/json").content(payload(8, "2023-W31", 8)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void newEvaluationAndDeactivationAreOrderedThroughCommit(boolean deactivationFirst) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(this.transactionManager);
        int[] ids = tx.execute(status -> {
            Student seed = this.em.find(Student.class, 7);
            Student evaluator = freshStudent(seed); Student target = freshStudent(seed);
            return new int[]{evaluator.getId(), target.getId()};
        });
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> tx.executeWithoutResult(status -> {
                if (deactivationFirst) deactivateAsAdmin(ids[1]); else createEvaluation(ids[0], ids[1]);
                firstApplied.countDown(); await(releaseFirst);
            }));
            Future<?> second = null;
            try {
                assertThat(firstApplied.await(10, TimeUnit.SECONDS)).isTrue();
                second = executor.submit(() -> tx.executeWithoutResult(status -> {
                    // Preload the old committed state, as the request DTO converter can do.
                    this.em.find(Student.class, ids[1]);
                    secondAttempting.countDown();
                    if (deactivationFirst) createEvaluation(ids[0], ids[1]); else deactivateAsAdmin(ids[1]);
                }));
                assertThat(secondAttempting.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> pending = second;
                assertThatThrownBy(() -> pending.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { releaseFirst.countDown(); }
            first.get(10, TimeUnit.SECONDS);
            if (deactivationFirst) {
                Future<?> rejected = second;
                assertThatThrownBy(() -> rejected.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(PeerEvaluationIllegalArgumentException.class);
            } else second.get(10, TimeUnit.SECONDS);
            Boolean evaluationPresent = tx.execute(status -> this.repository.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W32", ids[0], ids[1]).isPresent());
            Boolean enabled = tx.execute(status -> this.em.find(Student.class, ids[1]).isEnabled());
            assertThat(evaluationPresent).isEqualTo(!deactivationFirst);
            assertThat(enabled).isFalse();
        } finally {
            tx.executeWithoutResult(status -> {
                this.repository.findByWeekAndEvaluatorId("2023-W32", ids[0]).forEach(this.repository::delete);
                this.repository.flush();
                this.studentRepository.deleteById(ids[0]); this.studentRepository.deleteById(ids[1]);
            });
        }
    }

    private Student freshStudent(Student seed) {
        String email = UUID.randomUUID() + "@abc.edu";
        Student student = new Student(email, "Concurrent", "Student", email, seed.getPassword(), true, "student");
        student.setSection(seed.getSection()); student.setTeam(seed.getTeam());
        return this.studentRepository.saveAndFlush(student);
    }
    private void createEvaluation(int evaluator, int target) {
        this.evaluations.addPeerEvaluation(new PeerEvaluation("2023-W32", this.em.find(Student.class, evaluator),
                this.em.find(Student.class, target), ratings(9,9,9,9,9,9), "Public", "Private"));
    }
    private void deactivateAsAdmin(int id) {
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("b.wei@abc.edu").claim("userId", 1).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_admin"))));
        try { this.students.deactivateStudent(id); } finally { SecurityContextHolder.clearContext(); }
    }
    private PeerEvaluationAverage summary(String week, int id) {
        return this.evaluations.generateWeeklyPeerEvaluationReportForSection(2, week).getPeerEvaluationAverages().stream()
                .filter(s -> s.getStudentId()==id).findFirst().orElseThrow();
    }
    private List<Rating> ratings(double... scores) {
        return java.util.stream.IntStream.range(0, scores.length).mapToObj(i -> new Rating(
                this.em.find(team.projectpulse.rubric.Criterion.class, i+1), scores[i])).toList();
    }
    private String payload(int evaluatee, String week, int score) {
        String ratings = java.util.stream.IntStream.rangeClosed(1,6).mapToObj(i -> "{\"criterionId\":"+i+",\"actualScore\":"+score+"}").collect(java.util.stream.Collectors.joining(","));
        return "{\"week\":\""+week+"\",\"evaluatorId\":7,\"evaluateeId\":"+evaluatee+",\"ratings\":["+ratings+"],\"publicComment\":\"Updated\",\"privateComment\":\"Private\"}";
    }
    private void deactivate(int id) throws Exception {
        this.mvc.perform(post(this.base + "/students/"+id+"/deactivate").header("Authorization",login("b.wei@abc.edu"))).andExpect(status().isOk());
        this.em.flush(); // The rollback test owns the transaction; persist the completed request's state before refresh.
    }
    private String login(String email) throws Exception {
        var r=this.mvc.perform(post(this.base+"/users/login").with(httpBasic(email,"123456"))).andReturn();
        return "Bearer "+new JSONObject(r.getResponse().getContentAsString()).getJSONObject("data").getString("token");
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Transaction barrier timed out"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
