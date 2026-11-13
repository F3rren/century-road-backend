package centuryroad.history.service;

import java.util.ArrayList;
import java.util.List;

/**
 * What is wrong with the editorial content, collected instead of thrown at the first mistake:
 * somebody fixing a file by hand wants the whole list, not one problem per restart.
 */
final class EditorialProblems {

    private final List<String> found = new ArrayList<>();

    void add(String problem) {
        found.add(problem);
    }

    /** Stops the service, with every problem listed, when there is any. */
    void failIfAny() {
        if (!found.isEmpty()) {
            throw new IllegalStateException("The editorial content is not valid:\n - " + String.join("\n - ", found));
        }
    }
}
