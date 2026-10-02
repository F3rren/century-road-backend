package centuryroad.auth.security;

import centuryroad.auth.model.Role;

/**
 * What JwtAuthFilter puts in the SecurityContext after verifying a token -
 * everything a
 * controller needs about the caller without a database round trip, since the
 * claims
 * already carry it.
 */
public record AppPrincipal(Long id, String email, Role role) {
}
