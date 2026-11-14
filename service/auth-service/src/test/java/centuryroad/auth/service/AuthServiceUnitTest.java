package centuryroad.auth.service;

import centuryroad.auth.exception.AuthenticationFailedException;
import centuryroad.auth.model.User;
import centuryroad.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The BCrypt work factor is written inside each hash, so raising it never locks anybody out: an
 * older hash still verifies, and is replaced by one at the current factor at the next good login,
 * the only moment the plain password exists. Real encoders at both factors, a mocked repository:
 * no application context needed for a decision this small.
 */
class AuthServiceUnitTest {

    private static final String EMAIL = "reader@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    private final BCryptPasswordEncoder current = new BCryptPasswordEncoder(12);
    private final BCryptPasswordEncoder older = new BCryptPasswordEncoder(10);
    private final UserRepository users = mock(UserRepository.class);
    private final AuthService service = new AuthService(users, current);

    private User userWithHash(String hash) {
        User user = new User();
        user.setEmail(EMAIL);
        user.setPassword(hash);
        user.setEnabled(true);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void aHashMadeAtALowerCostIsReplacedByOneAtTheCurrentCostAfterAGoodLogin() {
        User user = userWithHash(older.encode(PASSWORD));
        String before = user.getPassword();

        assertThat(service.login(EMAIL, PASSWORD)).isSameAs(user);

        verify(users).save(user);
        assertThat(user.getPassword()).isNotEqualTo(before).startsWith("$2a$12$");
        assertThat(current.matches(PASSWORD, user.getPassword())).isTrue();
    }

    @Test
    void aHashAlreadyAtTheCurrentCostIsLeftAlone() {
        User user = userWithHash(current.encode(PASSWORD));
        String before = user.getPassword();

        service.login(EMAIL, PASSWORD);

        verify(users, never()).save(any());
        assertThat(user.getPassword()).isEqualTo(before);
    }

    @Test
    void aRefusedLoginNeverTouchesTheStoredHash() {
        User user = userWithHash(older.encode(PASSWORD));
        String before = user.getPassword();

        assertThatThrownBy(() -> service.login(EMAIL, "not the password"))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(users, never()).save(any());
        assertThat(user.getPassword()).isEqualTo(before);
    }
}
