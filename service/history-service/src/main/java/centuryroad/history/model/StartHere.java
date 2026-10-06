package centuryroad.history.model;

import java.util.List;

/** The file src/main/resources/editorial/start-here.json: the handful of proposals "Inizia da qui" shows. */
public record StartHere(List<StartHerePick> picks) {

    public StartHere {
        picks = picks == null ? List.of() : List.copyOf(picks);
    }
}
