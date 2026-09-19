package centuryroad.history.query;

import centuryroad.history.exception.InvalidRequestException;
import centuryroad.history.model.Entry;
import centuryroad.history.model.Language;
import centuryroad.history.model.Section;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.MonthDay;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class OnThisDayQueryUnitTest {

    private static OnThisDayQuery query(int month, int day, String lang, List<String> types,
                                        Integer year, Integer from, Integer to) {
        return OnThisDayQuery.of(month, day, lang, types, year, from, to);
    }

    private static OnThisDayQuery dayOnly(int month, int day) {
        return query(month, day, "it", null, null, null, null);
    }

    private static void assertRejected(Runnable call, String errorCode) {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(call::run)
                .extracting(InvalidRequestException::getErrorCode)
                .isEqualTo(errorCode);
    }

    // ----- date -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"1,1", "2,29", "4,30", "12,31", "10,16"})
    void anyRealDayIsAccepted_includingTheLeapDay(int month, int day) {
        assertThat(dayOnly(month, day).day()).isEqualTo(MonthDay.of(month, day));
    }

    @ParameterizedTest
    @CsvSource({"2,30", "2,31", "4,31", "6,31", "13,1", "0,5", "1,0", "12,32", "-1,5", "1,-1"})
    void aDayThatDoesNotExistIsRejectedBeforeAnythingIsSentToWikipedia(int month, int day) {
        assertRejected(() -> dayOnly(month, day), "INVALID_DATE");
    }

    // ----- language ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"it", "en", "IT", "En"})
    void supportedLanguagesAreAcceptedInAnyCase(String lang) {
        assertThat(query(3, 4, lang, null, null, null, null).language()).isIn(Language.IT, Language.EN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr", "", "zz", "it.evil.example", "../../etc", "it/../en", "it wikipedia"})
    void anythingElseIsRejected_soAHostnameCanNeverBeSteered(String lang) {
        assertRejected(() -> query(3, 4, lang, null, null, null, null), "UNSUPPORTED_LANGUAGE");
    }

    // ----- types ------------------------------------------------------------------------

    @Test
    void noTypesMeansEveryTypeThereIs() {
        assertThat(dayOnly(3, 4).sections()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Section.class));
    }

    @Test
    void anEmptyListMeansEveryTypeToo() {
        assertThat(query(3, 4, "it", List.of(), null, null, null).sections())
                .hasSize(Section.values().length);
    }

    @Test
    void typesAreParsedIgnoringCaseAndSpaces_andDuplicatesCollapse() {
        assertThat(query(3, 4, "it", List.of("events", " BIRTHS ", "events"), null, null, null).sections())
                .containsExactlyInAnyOrder(Section.EVENTS, Section.BIRTHS);
    }

    @Test
    void anUnknownTypeIsRejected_andTheAnswerListsTheValidOnes() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> query(3, 4, "it", List.of("events", "wars"), null, null, null))
                .satisfies(e -> {
                    assertThat(e.getErrorCode()).isEqualTo("INVALID_TYPE");
                    assertThat(e.getUserMessage()).contains("selected", "events", "births", "deaths", "holidays");
                });
    }

    // ----- years ------------------------------------------------------------------------

    @Test
    void noYearMeansNoFilter() {
        assertThat(dayOnly(3, 4).years().isBounded()).isFalse();
    }

    @Test
    void anExactYearIsARangeOfOne() {
        assertThat(query(3, 4, "it", null, 1978, null, null).years()).isEqualTo(new YearRange(1978, 1978));
    }

    @Test
    void aNegativeYearIsFine_becauseTheFeedHasThemForBeforeTheCommonEra() {
        assertThat(query(3, 15, "en", null, -44, null, null).years()).isEqualTo(new YearRange(-44, -44));
    }

    @Test
    void aRangeMayBeOpenAtEitherEnd() {
        assertThat(query(3, 4, "it", null, null, 1900, null).years())
                .isEqualTo(new YearRange(1900, OnThisDayQuery.MAX_YEAR));
        assertThat(query(3, 4, "it", null, null, null, 1900).years())
                .isEqualTo(new YearRange(OnThisDayQuery.MIN_YEAR, 1900));
    }

    @Test
    void theBoundsThemselvesAreAllowed() {
        assertThat(query(3, 4, "it", null, null, OnThisDayQuery.MIN_YEAR, OnThisDayQuery.MAX_YEAR).years().isBounded())
                .isTrue();
    }

    @Test
    void anExactYearAndARangeTogetherAreAmbiguous() {
        assertRejected(() -> query(3, 4, "it", null, 1978, 1900, null), "INVALID_YEAR");
        assertRejected(() -> query(3, 4, "it", null, 1978, null, 2000), "INVALID_YEAR");
    }

    @Test
    void aRangeRunningBackwardsIsRejected() {
        assertRejected(() -> query(3, 4, "it", null, null, 2000, 1900), "INVALID_YEAR");
    }

    @ParameterizedTest
    @ValueSource(ints = {-10000, 10000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void aYearOutsideTheSupportedRangeIsRejected(int year) {
        assertRejected(() -> query(3, 4, "it", null, year, null, null), "INVALID_YEAR");
    }

    // ----- YearRange.apply --------------------------------------------------------------

    @Test
    void aBoundedRangeKeepsOnlyYearsInsideItAndEdgesCount() {
        List<Entry> entries = List.of(
                new Entry("a", 1899, List.of()), new Entry("b", 1900, List.of()),
                new Entry("c", 1950, List.of()), new Entry("d", 2000, List.of()), new Entry("e", 2001, List.of()));

        assertThat(new YearRange(1900, 2000).apply(entries)).extracting(Entry::text).containsExactly("b", "c", "d");
    }

    @Test
    void entriesWithNoYearAreDroppedOnceThereIsARange_butKeptWhenThereIsNone() {
        List<Entry> entries = List.of(new Entry("holiday", null, List.of()), new Entry("event", 1978, List.of()));

        assertThat(new YearRange(1900, 2000).apply(entries)).extracting(Entry::text).containsExactly("event");
        assertThat(YearRange.unbounded().apply(entries)).isSameAs(entries);
    }
}
