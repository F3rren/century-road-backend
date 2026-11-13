package centuryroad.history.service;

import centuryroad.history.model.StartHere;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads the editorial JSON files and nothing else: where they are, how they are parsed. What the
 * parsed values must satisfy is {@link EditorialValidator}'s business, so a change to the files'
 * layout and a change to the rules about their content no longer touch the same class.
 *
 * A file that cannot be read or parsed is not thrown: it is recorded as a problem and the rest
 * are still read, so the person editing sees everything that is wrong at once.
 */
final class EditorialLoader {

    /** A file read: its name, the topic folder it was found in, and what it holds. */
    record Loaded<T>(String file, String folder, T value) {

        /** Where to look for it, for a message: the folder and the name. */
        String where() {
            return folder + "/" + file;
        }
    }

    private final ResourcePatternResolver resolver;
    private final JsonMapper mapper;

    EditorialLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
        // Strict on purpose: Spring's own mapper ignores keys it does not know, which is how a
        // "befor" in a hand-written file would become an insight with no past. A number where an enum
        // belongs is refused too: it would be read as the value's position in the declaration.
        this.mapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .build();
    }

    <T> List<Loaded<T>> loadAll(String pattern, Class<T> type, EditorialProblems problems) {
        List<Loaded<T>> loaded = new ArrayList<>();
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            problems.add(pattern + ": cannot be listed (" + e.getMessage() + ")");
            return loaded;
        }
        // By name, then by place: the same name in two folders is a mistake that is reported, and always
        // in the same order.
        Arrays.sort(resources, Comparator.comparing((Resource r) -> String.valueOf(r.getFilename()))
                .thenComparing(EditorialLoader::folderOf));
        for (Resource resource : resources) {
            String file = String.valueOf(resource.getFilename());
            try (InputStream in = resource.getInputStream()) {
                T value = mapper.readValue(in, type);
                if (value == null) {
                    problems.add(file + ": is empty");
                } else {
                    loaded.add(new Loaded<>(file, folderOf(resource), value));
                }
            } catch (IOException e) {
                problems.add(file + ": cannot be read (" + e.getMessage() + ")");
            }
        }
        return loaded;
    }

    /** The "Inizia da qui" file; empty, with the reason recorded, when it is missing or unreadable. */
    Optional<StartHere> loadStartHere(String location, EditorialProblems problems) {
        Resource resource = resolver.getResource(location);
        if (!resource.exists()) {
            problems.add("start-here.json: missing");
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            StartHere file = mapper.readValue(in, StartHere.class);
            if (file == null) {
                problems.add("start-here.json: is empty");
            }
            return Optional.ofNullable(file);
        } catch (IOException e) {
            problems.add("start-here.json: cannot be read (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    /** The topic folder of a file found by "editorial/&#42;/insights/&#42;.json": two levels up. */
    private static String folderOf(Resource resource) {
        try {
            String[] parts = resource.getURL().getPath().split("/");
            return parts.length >= 3 ? parts[parts.length - 3] : "";
        } catch (IOException e) {
            return "";
        }
    }
}
