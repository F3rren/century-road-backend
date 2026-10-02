package centuryroad.history.service;

import centuryroad.history.model.Coordinates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The country lookup against the real bundled file. Every point below sits well inside its
 * country even at the 1:110m simplification (no coastal cities: Palermo falls in the sea at
 * this scale).
 */
class CountryLocatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CountryLocator LOCATOR = new CountryLocator(MAPPER);

    private static String at(double lat, double lon) {
        return LOCATOR.locate(new Coordinates(lat, lon)).orElse("none");
    }

    @Test
    void theBundledFileIsTheOneTheFrontendMapDraws() throws IOException, NoSuchAlgorithmException {
        // Natural Earth v3.3.0, the copy geojson.xyz serves to the frontend
        // (countries.ts). Change both together, or the map and the index disagree.
        try (InputStream in = new ClassPathResource(CountryLocator.RESOURCE).getInputStream()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            assertThat(HexFormat.of().formatHex(digest))
                    .isEqualTo("86f29e1058698894995c9c18f01ff85d76f87b3bbeea2198ec20c573d8096a36");
        }
    }

    @Test
    void aPointIsPlacedInTheCountryThatContainsIt() {
        assertThat(at(41.9028, 12.4964)).isEqualTo("IT"); // Rome
        assertThat(at(48.8566, 2.3522)).isEqualTo("FR"); // Paris
        assertThat(at(-26.2041, 28.0473)).isEqualTo("ZA"); // Johannesburg
    }

    @Test
    void everyPartOfAMultiPolygonCountryCounts() {
        assertThat(at(40.0, 9.0)).isEqualTo("IT"); // Sardinia
        assertThat(at(37.6, 14.0)).isEqualTo("IT"); // Sicily
    }

    @Test
    void aCountryWithinAnotherIsFound() {
        assertThat(at(-29.6, 28.2)).isEqualTo("LS"); // Lesotho, inside South Africa
    }

    @Test
    void aHoleIsAHole() {
        // Lesotho comes first in the file, so the lookup above would pass even if South
        // Africa's hole were filled in. Here South Africa is the only shape.
        JsonNode file = read();
        ObjectNode onlySouthAfrica = MAPPER.createObjectNode();
        onlySouthAfrica.putArray("features").add(StreamSupport.stream(file.path("features").spliterator(), false)
                .filter(f -> f.path("properties").path("iso_a2").asText().equals("ZA"))
                .findFirst().orElseThrow());
        CountryLocator southAfrica = new CountryLocator(onlySouthAfrica);

        assertThat(southAfrica.locate(new Coordinates(-29.6, 28.2))).isEmpty();
        assertThat(southAfrica.locate(new Coordinates(-26.2041, 28.0473))).contains("ZA");
    }

    @Test
    void shapesWithoutAnIsoCodeAreLeftOut_likeOnTheMap() {
        assertThat(at(42.6629, 21.1655)).isEqualTo("none"); // Pristina, Kosovo ("-99")
        assertThat(at(9.56, 44.065)).isEqualTo("none"); // Hargeisa, Somaliland ("-99")
        assertThat(at(35.3417, 33.3167)).isEqualTo("none"); // Kyrenia, Northern Cyprus ("-99")
    }

    @Test
    void thePointAtSeaIsInNoCountry() {
        assertThat(at(0, -30)).isEqualTo("none");
    }

    private static JsonNode read() {
        try (InputStream in = new ClassPathResource(CountryLocator.RESOURCE).getInputStream()) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
