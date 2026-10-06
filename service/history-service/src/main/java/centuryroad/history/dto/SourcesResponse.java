package centuryroad.history.dto;

import centuryroad.history.model.GuidedPath;
import centuryroad.history.model.Insight;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * "Il progetto e le fonti", the part that is data rather than prose: where every piece of
 * content comes from and under what terms, how much of it there is, and what it leaves out.
 * The page that tells the story of the project is the frontend's; this is the provenance it
 * can show next to it, computed from the real state of the service rather than written once
 * and left to go stale.
 *
 * Provenance, not a label of reliability: nothing here says "verified". Only an insight with
 * a review date was checked by a person, and the answer counts those.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Where the content comes from, under what terms, how much there is and what it leaves out.")
public record SourcesResponse(
        @Schema(description = "The sources of everything the service shows, with the terms of each.") List<DataSource> sources,
        @Schema(description = "How much content there is, as it stands now.") Coverage coverage,
        @Schema(description = "What the content is not, in Italian. Show it on the project page, in full.") List<Limit> limits) {

    @Schema(description = "One source of content.")
    public record DataSource(
            @Schema(description = "A stable identifier.", example = "wikipedia-on-this-day") String id,
            @Schema(description = "Its name.", example = "Wikipedia - Accadde oggi") String name,
            @Schema(description = "What the service takes from it, in Italian.") String provides,
            @Schema(description = "Where it is.", example = "https://it.wikipedia.org/") String url,
            @Schema(description = "The licence it is under. \"Per file\" when each file has its own. Absent when the service has not declared one.", example = "CC BY-SA 4.0") String license,
            @Schema(description = "The licence itself.") String licenseUrl,
            @Schema(description = "Whether showing it requires a credit and a link back.") boolean attributionRequired) {
    }

    @Schema(description = "How much content there is.")
    public record Coverage(
            @Schema(description = "The country index, per Wikipedia edition: how many events, how many countries, which years, how recent. Empty until its first pass has run.") List<IndexCoverage> index,
            @Schema(description = "The hand-written content.") Editorial editorial) {
    }

    @Schema(description = "The hand-written content: guided paths and insights.")
    public record Editorial(
            @Schema(description = "How many guided paths.", example = "1") int paths,
            @Schema(description = "How many insights (\"Perché conta\").", example = "9") int insights,
            @Schema(description = "How many of them have been reviewed by a person against their sources. The others are drafts, and say so by having no review date.", example = "0") int reviewedInsights,
            @Schema(description = "The day of the most recent review. Absent when nothing was ever reviewed.") LocalDate lastReviewedAt) {
    }

    @Schema(description = "One thing the content is not.")
    public record Limit(
            @Schema(description = "A stable identifier.", example = "NOT_VERIFIED") String code,
            @Schema(description = "The limit, in Italian, to show as it is.") String message) {
    }

    private static final String CC_BY_SA = "CC BY-SA 4.0";
    private static final String CC_BY_SA_URL = "https://creativecommons.org/licenses/by-sa/4.0/";

    static final List<DataSource> SOURCES = List.of(
            new DataSource("wikipedia-on-this-day", "Wikipedia - Accadde oggi",
                    "Gli eventi, le nascite, le morti e le ricorrenze di ogni giorno, con gli articoli collegati, "
                            + "nelle edizioni italiana e inglese.",
                    "https://it.wikipedia.org/", CC_BY_SA, CC_BY_SA_URL, true),
            new DataSource("wikimedia-commons", "Wikimedia Commons",
                    "Le immagini: solo file ospitati su Commons, ciascuno con la propria licenza e il proprio autore, "
                            + "indicati nella pagina del file.",
                    "https://commons.wikimedia.org/", "Per file", null, true),
            new DataSource("natural-earth", "Natural Earth",
                    "I confini dei paesi (scala 1:110m) con cui la mappa e l'indice per paese collocano gli eventi.",
                    "https://www.naturalearthdata.com/", "Pubblico dominio",
                    "https://www.naturalearthdata.com/about/terms-of-use/", false),
            new DataSource("century-road-editorial", "Century Road - percorsi e approfondimenti",
                    "I percorsi guidati e i blocchi «Perché conta», scritti a mano a partire dalle fonti elencate in ciascuno.",
                    null, null, null, false));

    static final List<Limit> LIMITS = List.of(
            new Limit("NOT_A_COMPLETE_LIST", "Gli eventi sono quelli che Wikipedia elenca nelle pagine «Accadde oggi»: "
                    + "non sono un elenco completo di ciò che accadde, né una selezione fatta da Century Road."),
            new Limit("NOT_VERIFIED", "Century Road non verifica i testi di Wikipedia: ne mostra la provenienza, non "
                    + "un'etichetta di affidabilità. Solo un approfondimento con una data di revisione è stato controllato "
                    + "da una persona sulle sue fonti."),
            new Limit("ONLY_PLACED_EVENTS", "Le viste per paese e «Nello stesso periodo» contengono solo gli eventi con "
                    + "un anno il cui luogo la mappa riesce a collocare in un paese: circa la metà. Restano fuori quelli "
                    + "in mare e quelli in territori che la mappa non ha, come il Kosovo."),
            new Limit("TODAYS_COUNTRIES", "I paesi sono quelli di oggi: un evento antico è attribuito al paese in cui "
                    + "cade oggi il suo luogo, non a quello che esisteva allora."),
            new Limit("ITALIAN_FEED_GAPS", "L'edizione italiana di Wikipedia non ha nascite e morti: arrivano dall'inglese "
                    + "e sono indicate come tali."),
            new Limit("INDEX_LAGS", "L'indice per paese viene ricostruito ogni notte: può essere indietro di un giorno "
                    + "rispetto a Wikipedia."),
            new Limit("APPROXIMATE_DATES_AND_PLACES", "Una data può dipendere dal fuso orario e un luogo può essere solo "
                    + "quello di partenza di un evento avvenuto altrove, come la Luna: quando accade, la scheda lo dice."));

    public static SourcesResponse from(List<IndexCoverage> index, List<GuidedPath> paths, List<Insight> insights) {
        List<LocalDate> reviews = insights.stream()
                .map(insight -> insight.provenance().reviewedAt())
                .filter(Objects::nonNull)
                .toList();
        Editorial editorial = new Editorial(paths.size(), insights.size(), reviews.size(),
                reviews.stream().max(LocalDate::compareTo).orElse(null));
        return new SourcesResponse(SOURCES, new Coverage(index, editorial), LIMITS);
    }
}
