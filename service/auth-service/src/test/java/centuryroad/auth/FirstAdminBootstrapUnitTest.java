package centuryroad.auth;

import centuryroad.auth.model.Role;
import centuryroad.auth.model.User;
import centuryroad.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three branches of the bootstrap mechanism. A plain CommandLineRunner with
 * constructor injection, so the whole decision table is reachable without an
 * application
 * context - which matters here, because every combination needs a different
 * pair of
 * property values and a context each would be an expensive way to test four if
 * statements.
 * FirstAdminBootstrapTest covers the half this cannot: that Spring actually
 * runs it.
 */
class FirstAdminBootstrapUnitTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private FirstAdminBootstrap bootstrap(String email, String password) {
        return new FirstAdminBootstrap(userRepository, passwordEncoder, email, password);
    }

    @Test
    void anEmptyTableWithBothValuesSetGetsItsFirstAdmin() {
        when(userRepository.count()).thenReturn(0L);

        bootstrap("primo.admin@test.it", "una-password-lunga").run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getEmail()).isEqualTo("primo.admin@test.it");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getCreatedAt()).isNotNull();
    }

    @Test
    void thePasswordIsHashedNeverStoredAsGiven() {
        when(userRepository.count()).thenReturn(0L);

        bootstrap("primo.admin@test.it", "una-password-lunga").run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        String stored = saved.getValue().getPassword();
        assertThat(stored).isNotEqualTo("una-password-lunga");
        assertThat(passwordEncoder.matches("una-password-lunga", stored)).isTrue();
    }

    @Test
    void aTableThatAlreadyHasUsersIsLeftAlone() {
        // The mechanism is for an empty database only: on any other one it must not
        // quietly add an administrator nobody asked for.
        when(userRepository.count()).thenReturn(1L);

        bootstrap("primo.admin@test.it", "una-password-lunga").run();

        verify(userRepository, never()).save(any());
    }

    @Test
    void aBlankEmailLeavesTheMechanismInert() {
        bootstrap("", "una-password-lunga").run();

        verify(userRepository, never()).save(any());
        // Not even a count(): with either value unset there is nothing to decide.
        verify(userRepository, never()).count();
    }

    @Test
    void aBlankPasswordLeavesTheMechanismInert() {
        bootstrap("primo.admin@test.it", "   ").run();

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).count();
    }

    @Test
    void bothUnsetLeavesTheMechanismInert() {
        // The default state once the operator has cleared the two variables after the
        // first login, exactly as the class javadoc instructs.
        bootstrap("", "").run();

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).count();
    }
}
