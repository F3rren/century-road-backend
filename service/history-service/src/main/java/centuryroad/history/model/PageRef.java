package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The Wikipedia article an entry points at. url is the attribution link CC BY-SA asks
 *  for, so a page without a usable one is dropped rather than shown without credit. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageRef(String title, String description, String extract, String url, ImageRef thumbnail) {
}
