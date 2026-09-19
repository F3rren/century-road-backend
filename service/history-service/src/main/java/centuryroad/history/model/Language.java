package centuryroad.history.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The Wikipedia editions this service talks to. An enum rather than a configurable list
 * because the code ends up in a hostname (it.wikipedia.org): a fixed set means a caller
 * can never steer the request at a host of their choosing, and adding a language is a
 * deliberate act. It also deserves a look at how complete that edition's feed is - the
 * Italian one, for instance, carries no births or deaths at all.
 */
public enum Language {

    IT("it"),
    EN("en");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    /** Also how it is written in JSON: "it", not "IT". */
    @JsonValue
    public String code() {
        return code;
    }

    public static Optional<Language> fromCode(String code) {
        return Arrays.stream(values()).filter(l -> l.code.equalsIgnoreCase(code)).findFirst();
    }

    public static String supportedCodes() {
        return Arrays.stream(values()).map(Language::code).collect(Collectors.joining(", "));
    }
}
