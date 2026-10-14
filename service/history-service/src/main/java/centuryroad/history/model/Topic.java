package centuryroad.history.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What a guided path is about: one per path, from a closed list. The order of the constants is
 * the order the paths are shown in - the periods first, oldest to newest, then regions, then
 * subjects - so adding a topic is deciding where it goes.
 *
 * A closed list on purpose: a typo in a hand-written file is a startup error naming the file,
 * not a path that quietly belongs to nothing. The label is Italian, like all editorial text;
 * the frontend may translate it, and falls back to this one for a code it does not know yet.
 *
 * Which topic a path belongs to, when it could go in two: the world wars and the Cold War go
 * to their own period whatever the region; one country's story across several periods goes to
 * the country; a subject that crosses two or more periods goes to the subject; otherwise the
 * period.
 */
public enum Topic {

    MONDO_ANTICO("Mondo antico"),
    ROMA_REPUBBLICANA("Roma repubblicana"),
    ROMA_IMPERIALE("Roma imperiale"),
    MEDIOEVO("Medioevo"),
    RINASCIMENTO("Rinascimento e Riforma"),
    ETA_MODERNA("Assolutismo, Lumi e rivoluzioni"),
    OTTOCENTO("Ottocento"),
    PRIMA_GUERRA_MONDIALE("Prima guerra mondiale"),
    TRA_LE_DUE_GUERRE("Tra le due guerre"),
    SECONDA_GUERRA_MONDIALE("Seconda guerra mondiale"),
    GUERRA_FREDDA("Guerra fredda"),
    OGGI("Dal 1989 a oggi"),
    ITALIA("Italia"),
    ASIA("Asia"),
    MONDO_ISLAMICO("Mondo islamico"),
    AFRICA("Africa"),
    AMERICHE("Americhe"),
    SCIENZA("Scienza"),
    MEDICINA("Medicina ed epidemie"),
    ESPLORAZIONI_E_SPAZIO("Esplorazioni e spazio"),
    TECNOLOGIA_ED_ECONOMIA("Tecnologia ed economia"),
    ARTE_E_CULTURA("Arte, letteratura e musica"),
    RELIGIONI_E_IDEE("Religioni e idee"),
    DIRITTI_E_SOCIETA("Diritti e società"),
    CITTA_E_LUOGHI("Città e luoghi");

    private final String label;

    Topic(String label) {
        this.label = label;
    }

    /** The name to show, in Italian. */
    public String label() {
        return label;
    }

    /**
     * The folder of src/main/resources/editorial that holds this topic's paths and insights:
     * the constant's name in lowercase, with hyphens (ROMA_REPUBBLICANA becomes roma-repubblicana).
     */
    public String folder() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The topic whose folder this is, if it is the folder of one. */
    public static Optional<Topic> ofFolder(String folder) {
        return Arrays.stream(values()).filter(topic -> topic.folder().equals(folder)).findFirst();
    }
}
