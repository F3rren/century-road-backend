package centuryroad.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Wikipedia responses used by the tests. They are invented, not copied from Wikipedia, but
 * they have the shape the live API has: [] and {} mixed for empty sections, holidays with no
 * year, entries with no pages, a Commons thumbnail beside a local one, HTML fields the
 * service is expected to drop.
 */
public final class Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    public static String text(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(String name) {
        try {
            return MAPPER.readTree(text(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
