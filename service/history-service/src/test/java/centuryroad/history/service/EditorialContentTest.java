package centuryroad.history.service;

import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Insight;
import centuryroad.history.model.StartHerePick;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real content, the files in src/main/resources/editorial, run through the same checks
 * the service makes at startup. This is what stops a typo in a hand-written file from becoming
 * a production that does not start: it fails here, in CI, naming the file and the problem.
 */
class EditorialContentTest {

    private final EditorialCatalog catalog = new EditorialCatalog(new PathMatchingResourcePatternResolver(),
            EditorialCatalog.DEFAULT_ROOT);

    @Test
    void theRealContentIsValid_andHasAtLeastOneCompletePath() {
        assertThat(catalog.paths()).isNotEmpty();
        assertThat(catalog.insights()).isNotEmpty();
        assertThat(catalog.startHere()).isNotEmpty();
    }

    @Test
    void everyInsightIsReachableFromTheCatalogByItsOwnSlug() {
        for (Insight insight : catalog.insights()) {
            assertThat(catalog.insight(insight.slug())).contains(insight);
        }
    }

    @Test
    void everyPathHasAReadingTimeAndStopsThatOpenRealInsights() {
        for (GuidedPath path : catalog.paths()) {
            assertThat(catalog.readingMinutes(path)).as(path.slug()).isBetween(1, 60);
            path.stops().forEach(stop -> assertThat(catalog.insight(stop.insight())).isPresent());
        }
    }

    @Test
    void theStartHereProposalsPointAtRealThings() {
        for (StartHerePick pick : catalog.startHere()) {
            if (pick.type() == StartHerePick.Type.PATH) {
                assertThat(catalog.path(pick.slug())).as(pick.slug()).isPresent();
            } else {
                assertThat(catalog.insight(pick.slug())).as(pick.slug()).isPresent();
            }
        }
    }
}
