package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One line of a section. year is null for holidays, and negative for years before the
 *  common era. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Entry(String text, Integer year, List<PageRef> pages) {

    public Entry {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }
}
