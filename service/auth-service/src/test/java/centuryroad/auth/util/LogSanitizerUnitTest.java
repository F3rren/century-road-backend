package centuryroad.auth.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The masking that keeps personal data out of the logs. Every branch here is a
 * case the
 * class javadoc promises never throws: a logging helper that fails would take
 * the
 * caller's flow down with it, over nothing more than a line of output.
 */
class LogSanitizerUnitTest {

    @Test
    void anOrdinaryEmailKeepsOnlyItsFirstLetterAndItsDomain() {
        assertThat(LogSanitizer.maskEmail("mario.rossi@example.it")).isEqualTo("m***@example.it");
    }

    @Test
    void nothingOfTheLocalPartSurvivesPastTheFirstLetter() {
        assertThat(LogSanitizer.maskEmail("mario.rossi@example.it")).doesNotContain("ario.rossi");
    }

    @Test
    void theSameAddressAlwaysMasksTheSameWay() {
        // The whole point of masking rather than dropping the value: several log lines
        // about one user still line up as the same user.
        assertThat(LogSanitizer.maskEmail("mario.rossi@example.it"))
                .isEqualTo(LogSanitizer.maskEmail("mario.rossi@example.it"));
    }

    @Test
    void twoDifferentAddressesOnTheSameDomainStayDistinguishable() {
        assertThat(LogSanitizer.maskEmail("anna@example.it"))
                .isNotEqualTo(LogSanitizer.maskEmail("mario@example.it"));
    }

    @Test
    void nullCollapsesToTheGenericMask() {
        assertThat(LogSanitizer.maskEmail(null)).isEqualTo("***");
    }

    @Test
    void inputTooShortToBeAnAddressCollapsesToTheGenericMask() {
        assertThat(LogSanitizer.maskEmail("")).isEqualTo("***");
        assertThat(LogSanitizer.maskEmail("a@")).isEqualTo("***");
    }

    @Test
    void inputWithNoAtSignCollapsesToTheGenericMask() {
        assertThat(LogSanitizer.maskEmail("not-an-email-at-all")).isEqualTo("***");
    }

    @Test
    void anAddressStartingWithTheAtSignCollapsesToTheGenericMask() {
        // charAt(0) would be the '@' itself here, so there is no first letter to keep.
        assertThat(LogSanitizer.maskEmail("@example.it")).isEqualTo("***");
    }
}
