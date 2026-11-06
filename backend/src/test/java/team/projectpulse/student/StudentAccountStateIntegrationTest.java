package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.section.Section;
import team.projectpulse.security.ActiveAccountJwtValidator;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.user.UserService;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared trunk only. Committed account fixtures are cleaned explicitly because tokens and independent
 * transactions must see actual commits (FR-SEC-active-account and stu.md's locking contract).
 * The security wiring commit adds HTTP bearer rejection to the shared validator checks.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StudentAccountStateIntegrationTest extends AbstractIntegrationTest {

    @Autowired StudentService studentService;
    @Autowired StudentRepository studentRepository;
    @Autowired UserService userService;
    @Autowired ActiveAccountJwtValidator validator;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MockMvc mockMvc;
    @Value("${api.endpoint.base-url}") String baseUrl;

    private Integer studentId;
    private String email;
    private TransactionTemplate transactions;

    @BeforeEach
    void createCommittedStudent() {
        this.transactions = new TransactionTemplate(this.transactionManager);
        this.email = "account-state-" + UUID.randomUUID() + "@abc.edu";
        this.studentId = this.transactions.execute(status -> {
            Student student = new Student(this.email, "Account", "State", this.email,
                    this.passwordEncoder.encode("Abc12345"), true, "student");
            student.setSection(this.entityManager.getReference(Section.class, 2));
            return this.studentRepository.saveAndFlush(student).getId();
        });
    }

    @AfterEach
    void removeCommittedStudent() {
        if (this.studentId != null) {
            this.transactions.executeWithoutResult(status -> this.studentRepository.deleteById(this.studentId));
        }
    }

    @Test
    void currentStatusRejectsAnAlreadyIssuedRealTokenAfterCommittedDeactivation() throws Exception {
        var response = this.mockMvc.perform(post(this.baseUrl + "/users/login")
                        .with(httpBasic(this.email, "Abc12345")))
                .andExpect(status().isOk()).andReturn();
        String raw = new JSONObject(response.getResponse().getContentAsString())
                .getJSONObject("data").getString("token");
        var token = this.jwtDecoder.decode(raw);
        assertThat(this.validator.validate(token).hasErrors()).isFalse();

        var admin = this.mockMvc.perform(post(this.baseUrl + "/users/login")
                .with(httpBasic("b.wei@abc.edu", "123456"))).andReturn();
        String adminToken = new JSONObject(admin.getResponse().getContentAsString()).getJSONObject("data").getString("token");
        this.mockMvc.perform(post(this.baseUrl + "/students/" + this.studentId + "/deactivate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("deactivated"));

        assertThat(this.validator.validate(token).hasErrors()).isTrue();
        assertThatThrownBy(() -> this.jwtDecoder.decode(raw)).isInstanceOf(JwtValidationException.class);
        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.studentId)
                        .header("Authorization", "Bearer " + raw))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        this.mockMvc.perform(put(this.baseUrl + "/students/" + this.studentId)
                        .header("Authorization", "Bearer " + raw).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false));
        this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(this.email, "Abc12345")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void accountLookupSeesCommittedStateInsteadOfCallersOlderSnapshot() {
        this.transactions.executeWithoutResult(status -> {
            Student cached = this.entityManager.find(Student.class, this.studentId);
            assertThat(cached.isEnabled()).isTrue();
            deactivateInNewTransaction();
            assertThat(cached.isEnabled()).isTrue();
            assertThat(this.userService.isAccountEnabled(this.studentId)).isFalse();
        });
    }

    @Test
    void lockingLookupRefreshesAnAlreadyManagedStudent() {
        this.transactions.executeWithoutResult(status -> {
            Student cached = this.entityManager.find(Student.class, this.studentId);
            deactivateInNewTransaction();
            assertThat(cached.isEnabled()).isTrue();
            Student locked = this.studentService.findStudentForUpdate(this.studentId);
            assertThat(locked).isSameAs(cached);
            assertThat(locked.isEnabled()).isFalse();
            assertThat(locked.getTeam()).isNull();
        });
    }

    @Test
    void studentLockIsHeldUntilCommitAndWaitingReaderGetsCommittedState() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch contenderLoaded = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> this.transactions.executeWithoutResult(status -> {
                Student student = this.studentService.findStudentForUpdate(this.studentId);
                student.setEnabled(false);
                locked.countDown();
                await(release);
            }));
            Future<Boolean> waiter = null;
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                waiter = executor.submit(() -> this.transactions.execute(status -> {
                    Student cached = this.entityManager.find(Student.class, this.studentId);
                    assertThat(cached.isEnabled()).isTrue();
                    contenderLoaded.countDown();
                    return this.studentService.findStudentForUpdate(this.studentId).isEnabled();
                }));
                assertThat(contenderLoaded.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Boolean> waiting = waiter;
                assertThatThrownBy(() -> waiting.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            assertThat(waiter.get(10, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void missingOrDeletedAccountIsNotEnabled() {
        this.transactions.executeWithoutResult(status -> this.studentRepository.deleteById(this.studentId));
        assertThat(this.userService.isAccountEnabled(this.studentId)).isFalse();
        assertThat(this.userService.isAccountEnabled(null)).isFalse();
        assertThat(this.userService.isAccountEnabled(-1)).isFalse();
        assertThatThrownBy(() -> this.studentService.findStudentForUpdate(this.studentId))
                .isInstanceOf(ObjectNotFoundException.class);
        this.studentId = null;
    }

    private void deactivateInNewTransaction() {
        TransactionTemplate separate = new TransactionTemplate(this.transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        separate.executeWithoutResult(status ->
                this.studentService.findStudentForUpdate(this.studentId).setEnabled(false));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for transaction barrier");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Transaction barrier interrupted", ex);
        }
    }
}
