package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One line of a section. text is the event itself. pages are the articles linked from it,
 *  in the order they appear in the text: related reading, not "the article about the
 *  event", which most events do not have. year is null for holidays, and negative for
 *  years before the common era. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Entry(String text, Integer year, List<PageRef> pages) {

    public Entry {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }
}
