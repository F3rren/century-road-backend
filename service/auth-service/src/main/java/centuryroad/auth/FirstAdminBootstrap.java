package centuryroad.auth;

import centuryroad.auth.model.Role;
import centuryroad.auth.model.User;
import centuryroad.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Creates the first administrator on startup, but ONLY if the users table is
 * empty and
 * ONLY if both bootstrap.admin.* values are set - on any other database, or
 * with either
 * left blank, the mechanism is inert. Without this there would be no way to
 * create the
 * first admin at all: user creation itself requires an admin token. Clear both
 * variables
 * after the first login.
 */
@Slf4j
@Component
public class FirstAdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public FirstAdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder,
            @Value("${bootstrap.admin.email:}") String adminEmail,
            @Value("${bootstrap.admin.password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            return;
        }
        if (userRepository.count() > 0) {
            log.debug("Users table is not empty - bootstrap admin mechanism stays inert");
            return;
        }

        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        admin.setCreatedAt(OffsetDateTime.now());
        userRepository.save(admin);

        log.info("First administrator created for an empty users table");
    }
}
