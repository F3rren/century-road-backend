package centuryroad.auth.service;

import centuryroad.auth.model.User;
import centuryroad.auth.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * The id-only, password-blind half of user management: lookup and deletion.
 * Kept apart
 * from AuthService, which is the only class that ever touches a password, so a
 * future
 * reviewer can see at a glance which of the two to check for anything
 * password-related.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User findById(Long id) {
        return userRepository.findById(id).orElse(null);
    }

    public void deleteById(Long id) {
        userRepository.deleteById(id);
    }
}
