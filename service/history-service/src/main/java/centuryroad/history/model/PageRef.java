package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A Wikipedia article linked from an entry's text. description and extract describe that
 *  article, not the event it was linked from. url is the attribution link CC BY-SA asks
 *  for, so a page without a usable one is dropped rather than shown without credit.
 *
 *  thumbnail and originalImage are the same picture at two sizes, both only ever from
 *  Commons; the original can be several megabytes, so its width and height are there for a
 *  frontend to decide whether to load it. coordinates is where the article's subject is, when
 *  it has a place. wikibaseItem is its Wikidata id (Q42), the same in every language. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageRef(String title, String description, String extract, String url,
                      ImageRef thumbnail, ImageRef originalImage, Coordinates coordinates,
                      String wikibaseItem) {
}
