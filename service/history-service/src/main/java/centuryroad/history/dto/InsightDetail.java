package centuryroad.history.dto;

import centuryroad.history.model.EventDate;
import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Insight;
import centuryroad.history.model.Language;
import centuryroad.history.model.Place;
import centuryroad.history.model.Provenance;
import centuryroad.history.model.Source;
import centuryroad.history.service.EditorialCatalog;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One "Perché conta" block, whole: the event told in three parts (before, the event, after),
 * with where it is, where to read next, and where each claim can be checked. Written by
 * hand, not taken from Wikipedia - which is why it carries its provenance.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "An event explained: what led up to it, what happened, what followed, and where to read next.")
public record InsightDetail(
        @Schema(description = "The insight's identifier.", example = "sputnik-1") String slug,
        @Schema(description = "The language of every text here. Editorial content is written in Italian only.") Language language,
        @Schema(description = "The event's title.") String title,
        @Schema(description = "The event in one or two sentences.") String summary,
        @Schema(description = "When it happened.") EventDate date,
        @Schema(description = "Where the map should go. Check `approximate`: the pin is sometimes a launch site.") Place place,
        @Schema(description = "\"Prima\": what prepared the event.") String before,
        @Schema(description = "\"L'evento\": what happened, in plain words.") String event,
        @Schema(description = "\"Dopo\": what followed. Developments, not all direct effects: the text says which is which.") String after,
        @Schema(description = "\"Collegamenti\": two or three insights worth reading next. Use them for \"Continua a esplorare\".") List<Related> related,
        @Schema(description = "The guided paths that have a stop on this insight, with its position: the way back to a path.") List<Membership> inPaths,
        @Schema(description = "Where each claim can be checked. Show them on the card, not only on a general page.") List<Source> sources,
        @Schema(description = "Caveats about dates and places - a date that depends on the time zone, a pin that is only a launch site. "
                + "Show them when there are any.") List<String> notes,
        @Schema(description = "Who wrote it and when it was last really reviewed.") Provenance provenance) {

    @Schema(description = "Another insight worth reading next.")
    public record Related(
            @Schema(description = "Its identifier.", example = "vostok-1-gagarin") String slug,
            @Schema(description = "Its title.") String title,
            @Schema(description = "When it happened.") EventDate date,
            @Schema(description = "Why it is worth reading next. A reason to read, not a claim of cause.") String reason) {
    }

    @Schema(description = "A guided path that has a stop on this insight.")
    public record Membership(
            @Schema(description = "The path's identifier.", example = "conquista-dello-spazio") String path,
            @Schema(description = "The path's title.") String title,
            @Schema(description = "This insight's place in the path, counting from 1.", example = "1") int position,
            @Schema(description = "How many stops the path has.", example = "9") int stopCount) {
    }

    public static InsightDetail from(Insight insight, EditorialCatalog catalog) {
        List<Related> related = insight.links().stream()
                .map(link -> {
                    Insight other = catalog.insight(link.slug()).orElseThrow();
                    return new Related(other.slug(), other.title(), other.date(), link.reason());
                })
                .toList();
        List<Membership> memberships = catalog.pathsContaining(insight.slug()).stream()
                .map(path -> membership(path, insight.slug()))
                .toList();
        return new InsightDetail(insight.slug(), Language.IT, insight.title(), insight.summary(), insight.date(),
                insight.place(), insight.before(), insight.event(), insight.after(), related, memberships,
                insight.sources(), insight.notes(), insight.provenance());
    }

    private static Membership membership(GuidedPath path, String insightSlug) {
        int position = 0;
        for (int i = 0; i < path.stops().size(); i++) {
            if (path.stops().get(i).insight().equals(insightSlug)) {
                position = i + 1;
            }
        }
        return new Membership(path.slug(), path.title(), position, path.stops().size());
    }
}
