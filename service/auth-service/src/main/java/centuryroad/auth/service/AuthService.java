package centuryroad.auth.service;

import centuryroad.auth.dto.CreateUserRequest;
import centuryroad.auth.dto.UpdateUserRequest;
import centuryroad.auth.exception.AuthenticationFailedException;
import centuryroad.auth.exception.DomainConflictException;
import centuryroad.auth.exception.ResourceNotFoundException;
import centuryroad.auth.model.Role;
import centuryroad.auth.model.User;
import centuryroad.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything that touches a password: authenticating one, and the admin-only
 * creation/
 * update of a user (UserService covers the id-only, password-blind operations -
 * lookup
 * and deletion - the split classroom-backend uses between the two classes).
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Returns the authenticated user, or throws - never null, so a caller cannot
     * forget to check and silently proceed as nobody. Deliberately the same
     * exception
     * and message whether the email does not exist or the password is wrong:
     * telling
     * the two apart would let a caller enumerate which emails are registered.
     */
    public User login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .filter(User::isEnabled)
                .orElseThrow(() -> new AuthenticationFailedException("No matching enabled user for " + email));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new AuthenticationFailedException("Wrong password for " + email);
        }
        return user;
    }

    @Transactional
    public User register(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DomainConflictException("Email already registered: " + request.email(),
                    "Questa email e' gia' registrata.");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(request.role() != null ? Role.valueOf(request.role().toUpperCase()) : Role.USER);
        user.setEnabled(true);
        user.setCreatedAt(OffsetDateTime.now());
        return userRepository.save(user);
    }

    @Transactional
    public User updateUser(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No user with id " + id,
                        "L'utente richiesto non esiste."));

        if (request.email() != null && !request.email().equals(user.getEmail())
                && userRepository.existsByEmail(request.email())) {
            throw new DomainConflictException("Email already registered: " + request.email(),
                    "Questa email e' gia' registrata.");
        }

        if (request.email() != null) {
            user.setEmail(request.email());
        }
        // Empty means "leave it unchanged" - an admin resetting every other field
        // should
        // not be forced to also know or invent a new password.
        if (request.password() != null && !request.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.password()));
        }
        if (request.role() != null) {
            user.setRole(Role.valueOf(request.role().toUpperCase()));
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        return userRepository.save(user);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }
}
