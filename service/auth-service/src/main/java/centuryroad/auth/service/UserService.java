package centuryroad.auth.service;

import centuryroad.auth.exception.ResourceNotFoundException;
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

    /** Throws ResourceNotFoundException when no user has the id: deleting nobody is not a success. */
    public void deleteById(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("No user with id " + id,
                    String.format("L'utente con ID %d non esiste.", id));
        }
        userRepository.deleteById(id);
    }
}
