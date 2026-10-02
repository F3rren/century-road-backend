package centuryroad.auth;

import centuryroad.auth.model.Role;
import centuryroad.auth.model.User;
import centuryroad.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half FirstAdminBootstrapUnitTest cannot reach: that Spring really runs
 * this
 * CommandLineRunner against a freshly migrated database, and that the
 * administrator it
 * leaves behind can actually log in. That last point is the whole mechanism -
 * creating a
 * user requires an admin token, so if this first login did not work there would
 * be no
 * way to get an administrator into an empty database at all.
 *
 * Unlike the other integration tests this one sets bootstrap.admin.*, so it
 * gets an
 * application context (and a container) of its own, and deliberately never
 * empties the
 * users table: the row under test is written once, at startup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "bootstrap.admin.email=primo.admin@test.it",
                "bootstrap.admin.password=una-password-lunga"
})
@Import({ TestcontainersConfiguration.class, RestTemplateTestConfiguration.class })
@ActiveProfiles("test")
class FirstAdminBootstrapTest {

        @Autowired
        private TestRestTemplate rest;
        @Autowired
        private UserRepository userRepository;
        @Autowired
        private ObjectMapper objectMapper;

        @Test
        void startingOnAnEmptyDatabaseLeavesExactlyOneEnabledAdmin() {
                User admin = userRepository.findByEmail("primo.admin@test.it").orElseThrow();

                assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
                assertThat(admin.isEnabled()).isTrue();
                assertThat(userRepository.count()).isEqualTo(1);
        }

        @Test
        @SuppressWarnings("unchecked")
        void thatAdministratorCanActuallyLogIn() throws Exception {
                ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                                Map.of("email", "primo.admin@test.it", "password", "una-password-lunga"), String.class);

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
                Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(resp.getBody(), Map.class)
                                .get("data");
                assertThat(data.get("token")).isNotNull();

                Map<String, Object> user = (Map<String, Object>) data.get("user");
                assertThat(user.get("role")).isEqualTo("ADMIN");
                // That the login matched at all is what proves the password was stored as a
                // usable bcrypt hash; this last line is the separate promise that the bootstrap
                // password does not come back out in a response.
                assertThat(resp.getBody()).doesNotContain("una-password-lunga");
        }
}
