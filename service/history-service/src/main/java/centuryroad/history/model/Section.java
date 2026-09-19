package centuryroad.history.model;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** The five lists Wikipedia's On this day feed is made of. The key is both the name
 *  Wikipedia uses in its response and the name this service uses in its own. */
public enum Section {

    SELECTED("selected"),
    EVENTS("events"),
    BIRTHS("births"),
    DEATHS("deaths"),
    HOLIDAYS("holidays");

    private final String key;

    Section(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<Section> fromKey(String key) {
        return Arrays.stream(values()).filter(s -> s.key.equalsIgnoreCase(key)).findFirst();
    }

    public static String supportedKeys() {
        return Arrays.stream(values()).map(Section::key).collect(Collectors.joining(", "));
    }
}
