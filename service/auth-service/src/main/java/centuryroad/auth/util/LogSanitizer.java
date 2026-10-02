package centuryroad.auth.util;

/**
 * Masking of personal data before it reaches the logs. Log files are archived,
 * copied
 * and often shared for debugging: writing an email in clear inside one
 * duplicates
 * personal data outside the database, where it is no longer subject to that
 * user's
 * deletion. Masking keeps the ability to correlate several lines about the same
 * user.
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /**
     * "mario.rossi@example.it" -> "m***@example.it". Null or malformed input
     * collapses
     * to "***" without throwing: a logging helper must never fail the caller's
     * flow.
     */
    public static String maskEmail(String email) {
        if (email == null || email.length() < 3) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}
