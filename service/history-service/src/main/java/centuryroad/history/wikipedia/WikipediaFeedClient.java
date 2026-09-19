package centuryroad.history.wikipedia;

import centuryroad.history.exception.UpstreamException;
import centuryroad.history.model.DayFeed;
import centuryroad.history.model.Language;

import java.time.MonthDay;

/**
 * The one door to Wikipedia. Everything above it (cache, service, controller) sees a
 * DayFeed or an UpstreamException and nothing else, so what Wikipedia actually is - URLs,
 * status codes, malformed bodies - stays behind this interface.
 */
public interface WikipediaFeedClient {

    /** The complete feed for one day in one language.
     *  @throws UpstreamException when Wikipedia cannot supply it */
    DayFeed fetchDay(Language language, MonthDay day);
}
