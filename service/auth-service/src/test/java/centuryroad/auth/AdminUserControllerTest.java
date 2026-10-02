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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin-only user management, and the one property every response here has to
 * keep:
 * the password never appears, in a list of many users just as much as in a
 * single one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, RestTemplateTestConfiguration.class })
@ActiveProfiles("test")
class AdminUserControllerTest {

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

    private String adminToken;
    private String userToken;
    private Long regularUserId;

    @BeforeEach
    void setUp() throws Exception {
        // Every test here logs in during setup: see AuthControllerTest.setUp for why
        // the
        // limiter has to be cleared rather than just the database.
        loginAttemptLimiter.clear();
        userRepository.deleteAll();
        save("admin@test.it", "admin-password", Role.ADMIN);
        String regularEmail = save("user@test.it", "user-password", Role.USER);
        regularUserId = userRepository.findByEmail(regularEmail).orElseThrow().getId();

        adminToken = login("admin@test.it", "admin-password");
        userToken = login("user@test.it", "user-password");
    }

    private String save(String email, String rawPassword, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(OffsetDateTime.now());
        userRepository.save(u);
        return email;
    }

    @SuppressWarnings("unchecked")
    private String login(String email, String password) throws Exception {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                Map.of("email", email, "password", password), String.class);
        Map<String, Object> body = objectMapper.readValue(resp.getBody(), Map.class);
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (String) data.get("token");
    }

    private ResponseEntity<String> call(String path, HttpMethod method, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(Objects.requireNonNull(token));
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAdminCanCreateAUser() throws Exception {
        ResponseEntity<String> resp = call("/api/admin/users", HttpMethod.POST, adminToken,
                Map.of("email", "nuovo@test.it", "password", "una-password-lunga", "role", "user"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(resp.getBody(), Map.class).get("data");
        assertThat(data.get("email")).isEqualTo("nuovo@test.it");
    }

    @Test
    void aRegularUserCannotCreateAUser() {
        ResponseEntity<String> resp = call("/api/admin/users", HttpMethod.POST, userToken,
                Map.of("email", "nuovo2@test.it", "password", "una-password-lunga", "role", "user"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anonymousCallsAreUnauthorizedNotForbidden() {
        ResponseEntity<String> resp = rest.getForEntity("/api/admin/users", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theUserListNeverExposesPasswords() throws Exception {
        ResponseEntity<String> resp = call("/api/admin/users", HttpMethod.GET, adminToken, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).doesNotContain("\"password\"")
                .doesNotContain("admin-password").doesNotContain("user-password");

        Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(resp.getBody(), Map.class).get("data");
        assertThat((List<Object>) data.get("users")).hasSize(2);
    }

    @Test
    void updatingAUserWithAnEmptyPasswordLeavesItUnchanged() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("email", "user@test.it");
        body.put("role", "user");
        // password left out entirely: an absent JSON field deserializes to null, which
        // AuthService.updateUser reads as "leave it unchanged".

        ResponseEntity<String> resp = call("/api/admin/users/" + regularUserId, HttpMethod.PUT, adminToken, body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login("user@test.it", "user-password")).isNotBlank();
    }

    @Test
    void creatingAUserWithAnEmailThatAlreadyExistsIsAConflict() {
        ResponseEntity<String> resp = call("/api/admin/users", HttpMethod.POST, adminToken,
                Map.of("email", "user@test.it", "password", "una-password-lunga", "role", "user"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aTooShortPasswordIsRejectedBeforeTheUserIsEverWritten() {
        ResponseEntity<String> resp = call("/api/admin/users", HttpMethod.POST, adminToken,
                Map.of("email", "nuovo3@test.it", "password", "corta", "role", "user"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userRepository.findByEmail("nuovo3@test.it")).isEmpty();
    }

    @Test
    void aMalformedEmailIsRejected() {
        ResponseEntity<String> resp = call("/api/admin/users", HttpMethod.POST, adminToken,
                Map.of("email", "non-e-una-email", "password", "una-password-lunga", "role", "user"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void renamingAUserOntoAnEmailAlreadyTakenIsAConflict() {
        ResponseEntity<String> resp = call("/api/admin/users/" + regularUserId, HttpMethod.PUT, adminToken,
                Map.of("email", "admin@test.it", "role", "user"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updatingAMissingUserAnswers404() {
        ResponseEntity<String> resp = call("/api/admin/users/999999", HttpMethod.PUT, adminToken,
                Map.of("email", "x@test.it", "role", "user"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingAUserRemovesItAndDeletingAgainAnswers404() {
        ResponseEntity<String> firstDelete = call("/api/admin/users/" + regularUserId, HttpMethod.DELETE, adminToken,
                null);
        ResponseEntity<String> secondDelete = call("/api/admin/users/" + regularUserId, HttpMethod.DELETE, adminToken,
                null);

        assertThat(firstDelete.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(userRepository.findById(regularUserId)).isEmpty();
    }
}
