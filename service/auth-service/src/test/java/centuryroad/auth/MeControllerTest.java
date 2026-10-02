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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /api/me - the caller's own profile, which had no test at all. The property
 * worth
 * pinning down is the one the controller's javadoc claims: it answers from the
 * current
 * database row rather than from the claims the token happens to carry, so a
 * change an
 * admin makes is visible without waiting for the token to expire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, RestTemplateTestConfiguration.class })
@ActiveProfiles("test")
class MeControllerTest {

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

    private String userToken;
    private Long userId;

    @BeforeEach
    void setUp() throws Exception {
        // See AuthControllerTest.setUp: the limiter is a singleton, and these classes
        // share one application context.
        loginAttemptLimiter.clear();
        userRepository.deleteAll();
        save("user@test.it", "user-password", Role.USER);
        userId = userRepository.findByEmail("user@test.it").orElseThrow().getId();
        userToken = login("user@test.it", "user-password");
    }

    private void save(String email, String rawPassword, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(OffsetDateTime.now());
        userRepository.save(u);
    }

    @SuppressWarnings("unchecked")
    private String login(String email, String password) throws Exception {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                Map.of("email", email, "password", password), String.class);
        Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(resp.getBody(), Map.class).get("data");
        return (String) data.get("token");
    }

    private ResponseEntity<String> getMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<String> resp) throws Exception {
        return (Map<String, Object>) objectMapper.readValue(resp.getBody(), Map.class).get("data");
    }

    @Test
    void aUserGetsTheirOwnProfile() throws Exception {
        ResponseEntity<String> resp = getMe(userToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(resp);
        assertThat(data.get("email")).isEqualTo("user@test.it");
        assertThat(data.get("role")).isEqualTo("USER");
        assertThat(((Number) data.get("id")).longValue()).isEqualTo(userId);
    }

    @Test
    void theProfileNeverCarriesThePassword() {
        ResponseEntity<String> resp = getMe(userToken);

        assertThat(resp.getBody()).doesNotContain("\"password\"").doesNotContain("user-password");
    }

    @Test
    void withoutATokenItIsUnauthorized() {
        ResponseEntity<String> resp = rest.getForEntity("/api/me", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aGarbageTokenIsUnauthorizedNotAServerError() {
        ResponseEntity<String> resp = getMe("not-a-real-token");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theProfileReflectsTheCurrentRowNotTheClaimsInTheToken() throws Exception {
        // The token still says USER; the row now says ADMIN. The endpoint hits the
        // database on purpose, so the response has to follow the row.
        User promoted = userRepository.findById(userId).orElseThrow();
        promoted.setRole(Role.ADMIN);
        userRepository.save(promoted);

        assertThat(dataOf(getMe(userToken)).get("role")).isEqualTo("ADMIN");
    }

    @Test
    void aTokenForAUserThatNoLongerExistsAnswers404() {
        userRepository.deleteById(userId);

        ResponseEntity<String> resp = getMe(userToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
