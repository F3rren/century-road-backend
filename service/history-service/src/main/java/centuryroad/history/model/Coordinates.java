package centuryroad.history.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** Where an article's subject is, in decimal degrees: latitude north and longitude east
 *  are positive. Only values that are a place on Earth are ever built. */
@Schema(description = "A place on Earth, in decimal degrees.")
public record Coordinates(
        @Schema(description = "Latitude, -90 to 90, positive north.", example = "33.8938") double lat,
        @Schema(description = "Longitude, -180 to 180, positive east.", example = "35.5018") double lon) {
}
