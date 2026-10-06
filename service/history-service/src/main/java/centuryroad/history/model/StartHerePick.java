package centuryroad.history.model;

/** One editorial proposal for a first-time visitor: a path or an insight, with the reason to open it. */
public record StartHerePick(Type type, String slug, String teaser) {

    public enum Type {
        PATH,
        INSIGHT
    }
}
