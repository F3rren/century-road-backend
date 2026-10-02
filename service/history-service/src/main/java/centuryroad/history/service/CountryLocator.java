package centuryroad.history.service;

import centuryroad.history.model.Coordinates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.geom.Path2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which country contains a point: the server-side twin of the frontend's countryGeometry.ts,
 * on the very same file (Natural Earth v3.3.0, 1:110m admin-0 countries, pinned by hash in
 * CountryLocatorTest), so the map's heatmap and the country index place an event in the same
 * country.
 *
 * Same rules as the frontend: shapes without a usable ISO A2 code ("-99": Northern Cyprus,
 * Kosovo, Somaliland) are left out, and the first shape in file order that contains the point
 * wins. Each shape is one even-odd Path2D holding all its rings, so a hole (Lesotho inside
 * South Africa) stays a hole. Path2D and turf can only disagree on a point lying exactly on a
 * border. java.awt.geom is plain geometry: it needs the java.desktop module, which the full
 * JRE runtime image ships, but no display.
 */
@Component
public class CountryLocator {

    static final String RESOURCE = "geo/ne_110m_admin_0_countries.geojson";

    private record Shape(String code, Path2D.Double outline) {
    }

    private final List<Shape> shapes;

    @Autowired
    public CountryLocator(ObjectMapper objectMapper) {
        this(read(objectMapper));
    }

    CountryLocator(JsonNode featureCollection) {
        List<Shape> loaded = new ArrayList<>();
        for (JsonNode feature : featureCollection.path("features")) {
            String code = feature.path("properties").path("iso_a2").asText("");
            if (code.isEmpty() || code.equals("-99")) {
                continue;
            }
            loaded.add(new Shape(code, outline(feature.path("geometry"))));
        }
        this.shapes = List.copyOf(loaded);
    }

    /** The ISO 3166-1 alpha-2 code of the country containing this point, if any. */
    public Optional<String> locate(Coordinates point) {
        return shapes.stream()
                .filter(shape -> shape.outline().contains(point.lon(), point.lat()))
                .map(Shape::code)
                .findFirst();
    }

    private static Path2D.Double outline(JsonNode geometry) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        JsonNode coordinates = geometry.path("coordinates");
        Iterable<JsonNode> polygons = "MultiPolygon".equals(geometry.path("type").asText())
                ? coordinates
                : List.of(coordinates);
        for (JsonNode polygon : polygons) {
            for (JsonNode ring : polygon) {
                addRing(path, ring);
            }
        }
        return path;
    }

    // GeoJSON positions are [longitude, latitude]: x is longitude, y is latitude.
    private static void addRing(Path2D.Double path, JsonNode ring) {
        if (ring.isEmpty()) {
            return;
        }
        path.moveTo(ring.get(0).get(0).asDouble(), ring.get(0).get(1).asDouble());
        for (int i = 1; i < ring.size(); i++) {
            path.lineTo(ring.get(i).get(0).asDouble(), ring.get(i).get(1).asDouble());
        }
        path.closePath();
    }

    private static JsonNode read(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the country shapes from " + RESOURCE, e);
        }
    }
}
