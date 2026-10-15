package centuryroad.auth;

import centuryroad.auth.model.Role;
import centuryroad.auth.model.User;
import centuryroad.auth.repository.UserRepository;
import centuryroad.auth.service.LoginAttemptLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Login, refresh and logout against a real Postgres via Testcontainers - the
 * constraint
 * on email uniqueness and the actual password hash comparison are exactly what
 * an H2 or
 * mocked run would not really exercise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, RestTemplateTestConfiguration.class })
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginAttemptLimiter loginAttemptLimiter;

    @BeforeEach
    void setUp() {
        // The limiter is in-memory and shared by every test in this class, so without
        // this the one test that trips it on purpose leaves every login that runs after
        // it answered with 429 instead of what the test expects.
        loginAttemptLimiter.clear();
        userRepository.deleteAll();
        save("user@test.it", "password-di-prova", Role.USER);
    }

    private void save(String email, String rawPassword, Role role) {
        save(email, rawPassword, role, true);
    }

    private void save(String email, String rawPassword, Role role, boolean enabled) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setEnabled(enabled);
        u.setCreatedAt(OffsetDateTime.now());
        userRepository.save(u);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readValue(resp.getBody(), Map.class);
    }

    /** The whole answer but the two fields that differ on every request: the time and the request id. */
    private Map<String, Object> answerOf(ResponseEntity<String> resp) throws Exception {
        Map<String, Object> body = new HashMap<>(bodyOf(resp));
        body.remove("timestamp");
        body.remove("sessionId");
        return body;
    }

    @Test
    void aCorrectLoginReturnsBothTokensAndTheUserSummary() throws Exception {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                Map.of("email", "user@test.it", "password", "password-di-prova"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = bodyOf(resp);
        assertThat(body.get("success")).isEqualTo(true);

        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("token")).isNotNull();
        assertThat(data.get("refreshToken")).isNotNull();
        assertThat(resp.getBody()).doesNotContain("password-di-prova").doesNotContain("\"password\"");

        Map<String, Object> user = (Map<String, Object>) data.get("user");
        assertThat(user.get("email")).isEqualTo("user@test.it");
    }

    @Test
    void aWrongPasswordIsRefusedWithTheSameCodeAsAnUnknownEmail() throws Exception {
        ResponseEntity<String> wrongPassword = rest.postForEntity("/api/auth/login",
                Map.of("email", "user@test.it", "password", "not-the-password"), String.class);
        ResponseEntity<String> unknownEmail = rest.postForEntity("/api/auth/login",
                Map.of("email", "nobody@test.it", "password", "whatever12"), String.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bodyOf(wrongPassword).get("error")).isEqualTo(bodyOf(unknownEmail).get("error"));
    }

    @Test
    void everyRefusedLoginAnswersWithTheSameBodyNotJustTheSameCode_andNeverEchoesTheEmail() throws Exception {
        // The code alone is not the whole answer: `message` is in the body too, and it used to say
        // "No matching enabled user for <email>" or "Wrong password for <email>" - which told an
        // anonymous caller which addresses are registered, and repeated theirs back.
        save("disabled@test.it", "password-di-prova", Role.USER, false);

        ResponseEntity<String> wrongPassword = rest.postForEntity("/api/auth/login",
                Map.of("email", "user@test.it", "password", "not-the-password"), String.class);
        ResponseEntity<String> unknownEmail = rest.postForEntity("/api/auth/login",
                Map.of("email", "nobody@test.it", "password", "whatever12"), String.class);
        ResponseEntity<String> disabled = rest.postForEntity("/api/auth/login",
                Map.of("email", "disabled@test.it", "password", "password-di-prova"), String.class);

        assertThat(answerOf(unknownEmail)).isEqualTo(answerOf(wrongPassword)).isEqualTo(answerOf(disabled));
        for (ResponseEntity<String> refused : List.of(wrongPassword, unknownEmail, disabled)) {
            assertThat(refused.getBody()).doesNotContain("@test.it").doesNotContain("Wrong password")
                    .doesNotContain("No matching");
        }
    }

    @Test
    void anEmailLongerThanAnAddressCanBeIsRefusedBeforeItIsKept() {
        String tooLong = "a".repeat(250) + "@test.it";

        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                Map.of("email", tooLong, "password", "whatever12"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDisabledUserIsRefusedEvenWithTheRightPassword() throws Exception {
        save("disabled@test.it", "password-di-prova", Role.USER, false);

        ResponseEntity<String> disabled = rest.postForEntity("/api/auth/login",
                Map.of("email", "disabled@test.it", "password", "password-di-prova"), String.class);
        ResponseEntity<String> unknownEmail = rest.postForEntity("/api/auth/login",
                Map.of("email", "nobody@test.it", "password", "whatever12"), String.class);

        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Same answer as an address that does not exist: whether an account is merely
        // disabled is not something an anonymous caller gets to find out.
        assertThat(bodyOf(disabled).get("error")).isEqualTo(bodyOf(unknownEmail).get("error"));
    }

    @Test
    void tooManyFailedAttemptsAreThrottled() {
        Map<String, String> badLogin = Map.of("email", "user@test.it", "password", "wrong");

        // The test profile's limit is 3 - see application-test.properties.
        for (int i = 0; i < 3; i++) {
            rest.postForEntity("/api/auth/login", badLogin, String.class);
        }
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login", badLogin, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.getHeaders().getFirst("Retry-After")).isNotNull();
        // The key is the caller's address and email; neither belongs in the answer.
        assertThat(resp.getBody()).doesNotContain("user@test.it").doesNotContain("127.0.0.1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshingRotatesBothTokensAndInvalidatesTheOldRefreshToken() throws Exception {
        Map<String, Object> loginData = (Map<String, Object>) bodyOf(rest.postForEntity("/api/auth/login",
                Map.of("email", "user@test.it", "password", "password-di-prova"), String.class)).get("data");
        String oldRefreshToken = (String) loginData.get("refreshToken");

        ResponseEntity<String> refreshed = rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", oldRefreshToken), String.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", oldRefreshToken), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshingWithGarbageIsUnauthorizedNotAServerError() {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", "not-a-real-token"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loggingOutRevokesTheRefreshTokenSoItCanNotBeUsedAgain() throws Exception {
        Map<String, Object> loginData = (Map<String, Object>) bodyOf(rest.postForEntity("/api/auth/login",
                Map.of("email", "user@test.it", "password", "password-di-prova"), String.class)).get("data");
        String refreshToken = (String) loginData.get("refreshToken");

        ResponseEntity<String> logout = rest.postForEntity("/api/auth/logout",
                Map.of("refreshToken", refreshToken), String.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> afterLogout = rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", refreshToken), String.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aUserDisabledAfterLoggingInCanNoLongerRefresh() throws Exception {
        Map<String, Object> loginData = (Map<String, Object>) bodyOf(rest.postForEntity("/api/auth/login",
                Map.of("email", "user@test.it", "password", "password-di-prova"), String.class)).get("data");
        String refreshToken = (String) loginData.get("refreshToken");

        User user = userRepository.findByEmail("user@test.it").orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);

        ResponseEntity<String> refreshed = rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", refreshToken), String.class);
        ResponseEntity<String> garbage = rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", "not-a-real-token"), String.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Indistinguishable from a token that never existed.
        assertThat(answerOf(refreshed)).isEqualTo(answerOf(garbage));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefreshTokenUsedByManyRequestsAtOnceWorksForExactlyOne() throws Exception {
        Map<String, Object> loginData = (Map<String, Object>) bodyOf(rest.postForEntity("/api/auth/login",
                Map.of("email", "user@test.it", "password", "password-di-prova"), String.class)).get("data");
        String refreshToken = (String) loginData.get("refreshToken");

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                Callable<HttpStatus> call = () -> {
                    go.await();
                    return HttpStatus.valueOf(rest.postForEntity("/api/auth/refresh",
                            Map.of("refreshToken", refreshToken), String.class).getStatusCode().value());
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            int ok = 0;
            for (Future<HttpStatus> result : results) {
                if (result.get() == HttpStatus.OK) {
                    ok++;
                }
            }
            // Single-use means one winner, however the requests interleave. A read followed by a
            // write lets several pass the read before any writes.
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
