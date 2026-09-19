package centuryroad.history.query;

import centuryroad.history.model.Entry;

import java.util.List;

/** An inclusive span of years. Wikipedia's feed cannot be asked for one, so this is applied
 *  to what comes back. */
public record YearRange(int from, int to) {

    private static final YearRange UNBOUNDED = new YearRange(Integer.MIN_VALUE, Integer.MAX_VALUE);

    public static YearRange unbounded() {
        return UNBOUNDED;
    }

    public boolean isBounded() {
        return !UNBOUNDED.equals(this);
    }

    /** Entries inside the span. Those with no year (holidays) belong to no year in particular,
     *  so once a span is set they are left out rather than guessed at. */
    public List<Entry> apply(List<Entry> entries) {
        if (!isBounded()) {
            return entries;
        }
        return entries.stream()
                .filter(entry -> entry.year() != null && entry.year() >= from && entry.year() <= to)
                .toList();
    }
}
