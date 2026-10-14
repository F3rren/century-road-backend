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

    private static final int MAX_LOGGED = 100;

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
        return oneLine(email.charAt(0) + "***" + email.substring(atIndex));
    }

    /**
     * What is kept of the address (its domain) is still whatever the caller typed, and a log
     * is read line by line: a line break in it would let a caller write a line of their own, and
     * an unbounded value would let them write a very long one. Control characters become "_",
     * and the result is cut at {@value #MAX_LOGGED}.
     */
    private static String oneLine(String value) {
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_LOGGED));
        for (int i = 0; i < value.length() && out.length() < MAX_LOGGED; i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? '_' : c);
        }
        return out.toString();
    }
}
