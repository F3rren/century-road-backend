package centuryroad.auth.model;

/**
 * The two application roles. Kept as a plain two-value enum, matching backend's
 * own
 * "role VARCHAR(20) DEFAULT 'USER'" column exactly (see
 * V1__baseline_schema.sql), so the
 * two never drift into accepting different strings from either side.
 */
public enum Role {
    ADMIN,
    USER;

    /**
     * The authority string Spring Security expects, e.g. "ROLE_ADMIN". Centralised
     * here
     * rather than concatenated wherever a role is checked, so the "ROLE_" prefix is
     * spelled out exactly once.
     */
    public String toAuthority() {
        return "ROLE_" + name();
    }
}
